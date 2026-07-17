package com.jhellerstedt.zulip.store

import com.jhellerstedt.zulip.api.DefaultZulipGateway
import com.jhellerstedt.zulip.api.ZulipApiException
import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.ZulipGateway
import com.jhellerstedt.zulip.data.AccountRepository
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val accountRepository: AccountRepository,
    private val cacheRepository: ZulipCacheRepository,
    private val gateway: ZulipGateway = DefaultZulipGateway(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coordinator = ZulipSyncCoordinator(
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
    )
    private val poller = EventPoller(scope, gateway, coordinator)
    private val switchMutex = Mutex()

    private val stores = mutableMapOf<Long, ZulipStore>()
    private val clients = mutableMapOf<Long, ZulipClient>()

    private val _activeAccount = MutableStateFlow<StoredZulipAccount?>(null)
    val activeAccount: StateFlow<StoredZulipAccount?> = _activeAccount.asStateFlow()

    private val _activeStore = MutableStateFlow<ZulipStore?>(null)
    val activeStore: StateFlow<ZulipStore?> = _activeStore.asStateFlow()

    var selectedNarrow: Narrow? = null

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
            if (_activeAccount.value?.id == accountId) {
                poller.stop()
                _activeStore.value = null
                _activeAccount.value = null
                accountRepository.setActiveAccountId(null)
            }
            stores.remove(accountId)
            clients.remove(accountId)?.close()
            withContext(Dispatchers.IO) {
                accountRepository.deleteAccount(accountId)
            }
            val remaining = accountRepository.getAllAccounts()
            if (remaining.isNotEmpty() && _activeAccount.value == null) {
                nextAccountId = remaining.first().id
            }
        }
        if (nextAccountId != null) {
            switchAccount(nextAccountId!!)
        }
    }

    fun shutdown() {
        poller.stop()
        clients.values.forEach { it.close() }
        clients.clear()
        stores.clear()
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

        val hydration = coordinator.hydrate(store)
        _activeStore.value = store
        if (hydration.hasCachedState) {
            scope.launch {
                coordinator.register(store)
                    .onSuccess { poller.start(store) }
            }
        } else {
            coordinator.register(store).getOrThrow()
            poller.start(store)
        }
        return store
    }
}
