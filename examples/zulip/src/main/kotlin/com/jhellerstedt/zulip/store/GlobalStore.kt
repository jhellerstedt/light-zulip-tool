package com.jhellerstedt.zulip.store

import com.jhellerstedt.zulip.api.DefaultZulipGateway
import com.jhellerstedt.zulip.api.ZulipApiException
import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.ZulipGateway
import com.jhellerstedt.zulip.data.OutboxSummary
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.data.ZulipAccountStore
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.data.ZulipOutboxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun shouldDowngradeIdleQueueTimeout(error: Throwable): Boolean {
    val apiError = error as? ZulipApiException.ApiError ?: return false
    return apiError.message?.contains("idle_queue_timeout", ignoreCase = true) == true &&
        apiError.message?.contains("not valid JSON", ignoreCase = true) == true
}

fun shouldReregisterEventQueue(error: Throwable): Boolean {
    val apiError = error as? ZulipApiException.ApiError ?: return false
    if (apiError.code.equals("BAD_EVENT_QUEUE_ID", ignoreCase = true)) return true
    return apiError.message?.contains("Bad event queue ID", ignoreCase = true) == true
}

class GlobalStore(
    private val accountRepository: ZulipAccountStore,
    private val cacheRepository: ZulipCacheRepository,
    private val gateway: ZulipGateway = DefaultZulipGateway(),
    private val coordinator: ZulipSyncCoordinator = ZulipSyncCoordinator(
        gateway = gateway,
        cache = cacheRepository,
        onFeatureLevelDowngraded = { downgraded ->
            withContext(Dispatchers.IO) {
                accountRepository.updateAccountMetadata(
                    id = downgraded.id,
                    realmName = downgraded.realmName,
                    zulipFeatureLevel = downgraded.zulipFeatureLevel,
                )
            }
        },
    ),
    private val outboxRepository: ZulipOutboxRepository? = null,
    private val outboxScheduler: OutboxScheduler? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val poller = EventPoller(scope, gateway, coordinator)
    private val switchMutex = Mutex()

    private val stores = mutableMapOf<Long, ZulipStore>()
    private val clients = mutableMapOf<Long, ZulipClient>()

    private val _activeAccount = MutableStateFlow<StoredZulipAccount?>(null)
    val activeAccount: StateFlow<StoredZulipAccount?> = _activeAccount.asStateFlow()

    private val _activeStore = MutableStateFlow<ZulipStore?>(null)
    val activeStore: StateFlow<ZulipStore?> = _activeStore.asStateFlow()

    var selectedNarrow: Narrow? = null

    suspend fun loadMessages(narrow: Narrow): List<com.jhellerstedt.zulip.api.models.ZulipMessage> {
        val store = requireNotNull(activeStore.value) { "No active account" }
        return coordinator.cachedMessages(store, narrow)
    }

    suspend fun refreshMessages(narrow: Narrow): Result<List<com.jhellerstedt.zulip.api.models.ZulipMessage>> {
        val store = requireNotNull(activeStore.value) { "No active account" }
        return coordinator.refreshMessages(store, narrow)
    }

    suspend fun loadTopics(streamId: Int): List<com.jhellerstedt.zulip.api.models.ZulipTopic> {
        val store = requireNotNull(activeStore.value) { "No active account" }
        return coordinator.cachedTopics(store, streamId)
    }

    suspend fun refreshTopics(streamId: Int): Result<List<com.jhellerstedt.zulip.api.models.ZulipTopic>> {
        val store = requireNotNull(activeStore.value) { "No active account" }
        return coordinator.refreshTopics(store, streamId)
    }

    suspend fun enqueueMessage(
        accountId: Long,
        narrow: Narrow,
        content: String,
        attachments: List<PendingUploadAttachment>,
    ): Result<String> {
        val repository = outboxRepository
            ?: return Result.failure(IllegalStateException("Zulip outbox repository is not registered"))
        val result = repository.enqueue(accountId, narrow, content, attachments)
        return result.map { localId ->
            tryScheduleOutbox(accountId)
            localId
        }
    }

    fun outboxSummary(accountId: Long): Flow<OutboxSummary> =
        outboxRepository?.observeSummary(accountId) ?: flowOf(OutboxSummary(pendingCount = 0, firstFailure = null))

    suspend fun retryOutboxMessage(messageId: String, accountId: Long) {
        outboxRepository?.retry(messageId)
        tryScheduleOutbox(accountId)
    }

    suspend fun discardOutboxMessage(messageId: String) {
        outboxRepository?.discard(messageId)
    }

    suspend fun initialize(): Result<ZulipStore> {
        val accounts = withContext(Dispatchers.IO) { accountRepository.getAllAccounts() }
        if (accounts.isEmpty()) return Result.failure(IllegalStateException("No accounts to restore"))
        val activeId = accountRepository.getActiveAccountId()
        val target = accounts.firstOrNull { it.id == activeId } ?: accounts.first()
        return switchAccount(target.id)
    }

    fun listAccounts(): List<StoredZulipAccount> = accountRepository.getAllAccounts()

    suspend fun switchAccount(accountId: Long): Result<ZulipStore> = switchMutex.withLock {
        runCatching { switchAccountLocked(accountId) }
    }

    suspend fun addAccount(
        realmUrl: String,
        realmName: String,
        email: String,
        apiKey: String,
        userId: Int,
        zulipFeatureLevel: Int,
    ): Result<ZulipStore> {
        val account = withContext(Dispatchers.IO) {
            accountRepository.insertAccount(
                realmUrl = realmUrl,
                realmName = realmName,
                email = email,
                apiKey = apiKey,
                userId = userId,
                zulipFeatureLevel = zulipFeatureLevel,
            )
        }
        return switchAccount(account.id)
    }

    suspend fun removeAccount(accountId: Long) {
        var nextAccountId: Long? = null
        switchMutex.withLock {
            outboxScheduler?.cancel(accountId)
            if (_activeAccount.value?.id == accountId) {
                poller.stop()
                _activeStore.value = null
                _activeAccount.value = null
                accountRepository.setActiveAccountId(null)
            }
            stores.remove(accountId)
            clients.remove(accountId)?.close()
            withContext(Dispatchers.IO) {
                outboxRepository?.deleteAccount(accountId)
                cacheRepository.deleteAccount(accountId)
                accountRepository.deleteAccount(accountId)
            }
            val remaining = accountRepository.getAllAccounts()
            if (remaining.isNotEmpty() && _activeAccount.value == null) {
                nextAccountId = remaining.first().id
            }
        }
        if (nextAccountId != null) {
            switchAccount(nextAccountId)
        }
    }

    fun shutdown() {
        poller.stop()
        clients.values.forEach { it.close() }
        clients.clear()
        stores.clear()
    }

    /**
     * Recovers the narrow case where local persistence succeeded but process death occurred
     * before WorkManager enqueueing: if an account has pending outbox rows when it becomes
     * active, make sure its worker is (re)scheduled.
     */
    private suspend fun scheduleOutboxWorkerIfPending(accountId: Long) {
        val repository = outboxRepository ?: return
        if (repository.observeSummary(accountId).first().pendingCount > 0) {
            tryScheduleOutbox(accountId)
        }
    }

    private fun tryScheduleOutbox(accountId: Long) {
        try {
            outboxScheduler?.enqueue(accountId)
        } catch (_: Exception) {
            // Best-effort; pending rows are recovered on account activation.
        }
    }

    private suspend fun switchAccountLocked(accountId: Long): ZulipStore {
        poller.stop()
        val account = requireNotNull(accountRepository.getAccount(accountId)) {
            "Account not found"
        }
        accountRepository.setActiveAccountId(accountId)
        _activeAccount.value = account

        val store = stores.getOrPut(accountId) {
            val apiKey = requireNotNull(accountRepository.getApiKey(accountId)) {
                "Missing API key"
            }
            val client = ZulipClient(account.realmUrl, account.email, apiKey)
            clients[accountId] = client
            ZulipStore(account, client)
        }
        store.updateAccountMetadata(account)
        scheduleOutboxWorkerIfPending(accountId)

        val hydration = coordinator.hydrate(store)
        _activeStore.value = store
        if (hydration.hasCachedState) {
            scope.launch {
                coordinator.register(store)
                    .onSuccess {
                        // The account may have been switched away from again while this
                        // background register was in flight; only (re)start the poller if
                        // `store` is still the active one, otherwise this would silently
                        // hijack the poller back onto a stale/previous account.
                        if (_activeStore.value === store) {
                            poller.start(store)
                        }
                    }
            }
        } else {
            coordinator.register(store).getOrThrow()
            poller.start(store)
        }
        return store
    }
}
