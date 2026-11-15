package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.DefaultZulipGateway
import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.ZulipGateway
import com.jhellerstedt.zulip.data.AccountRepository
import com.jhellerstedt.zulip.data.FileOutboxFileStore
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.data.ZulipDatabase
import com.jhellerstedt.zulip.data.ZulipPreferences
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.LightOutboxScheduler
import com.jhellerstedt.zulip.store.OutboxScheduler
import com.jhellerstedt.zulip.store.ZulipOutboxProcessor
import com.jhellerstedt.zulip.store.ZulipSyncCoordinator
import com.jhellerstedt.zulip.data.ZulipOutboxRepository
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ZulipServices {
    @Volatile
    private var globalStore: GlobalStore? = null

    @Volatile
    private var accountRepository: AccountRepository? = null

    @Volatile
    private var cacheRepository: ZulipCacheRepository? = null

    @Volatile
    private var database: ZulipDatabase? = null

    @Volatile
    private var gateway: ZulipGateway? = null

    @Volatile
    private var syncCoordinator: ZulipSyncCoordinator? = null

    @Volatile
    private var outboxRepository: ZulipOutboxRepository? = null

    @Volatile
    private var outboxProcessor: ZulipOutboxProcessor? = null

    @Volatile
    private var outboxScheduler: OutboxScheduler? = null

    @Volatile
    private var preferences: ZulipPreferences? = null

    private val asteriskPoke = MutableStateFlow(0)
    private var asteriskScope: CoroutineScope? = null
    private var notificationPermissionRequested = false

    fun database(context: SealedLightContext): ZulipDatabase {
        return database ?: synchronized(this) {
            // The library SDK opens the database with the class and name only.
            // A new install creates the current schema. Version stays 2 so a
            // database that already migrated locally still opens.
            database ?: context.buildDatabase(
                ZulipDatabase::class.java,
                AccountRepository.DATABASE_NAME,
            ).also { database = it }
        }
    }

    fun accountRepository(context: SealedLightContext): AccountRepository {
        return accountRepository ?: synchronized(this) {
            accountRepository ?: AccountRepository.getInstance(
                databaseProvider = { database(context) },
                dataStore = context.dataStore,
            ).also { accountRepository = it }
        }
    }

    fun cacheRepository(context: SealedLightContext): ZulipCacheRepository {
        return cacheRepository ?: synchronized(this) {
            cacheRepository ?: ZulipCacheRepository(database(context).cacheDao()).also { cacheRepository = it }
        }
    }

    fun gateway(): ZulipGateway {
        return gateway ?: synchronized(this) {
            gateway ?: DefaultZulipGateway().also { gateway = it }
        }
    }

    fun syncCoordinator(context: SealedLightContext): ZulipSyncCoordinator {
        return syncCoordinator ?: synchronized(this) {
            syncCoordinator ?: ZulipSyncCoordinator(
                gateway = gateway(),
                cache = cacheRepository(context),
                onFeatureLevelDowngraded = { downgraded ->
                    accountRepository(context).updateAccountMetadata(
                        id = downgraded.id,
                        realmName = downgraded.realmName,
                        zulipFeatureLevel = downgraded.zulipFeatureLevel,
                    )
                },
            ).also { syncCoordinator = it }
        }
    }

    fun outboxRepository(context: SealedLightContext): ZulipOutboxRepository {
        return outboxRepository ?: synchronized(this) {
            outboxRepository ?: ZulipOutboxRepository(
                dao = database(context).outboxDao(),
                files = FileOutboxFileStore(context.filesDir),
            ).also { outboxRepository = it }
        }
    }

    fun outboxProcessor(context: SealedLightContext): ZulipOutboxProcessor {
        return outboxProcessor ?: synchronized(this) {
            outboxProcessor ?: ZulipOutboxProcessor(
                repository = outboxRepository(context),
                gateway = gateway(),
                clientForAccount = { accountId ->
                    val account = requireNotNull(accountRepository(context).getAccount(accountId)) {
                        "Account $accountId does not exist"
                    }
                    val apiKey = requireNotNull(accountRepository(context).getApiKey(accountId)) {
                        "Account $accountId is missing an API key"
                    }
                    ZulipClient(account.realmUrl, account.email, apiKey)
                },
            ).also { outboxProcessor = it }
        }
    }

    fun outboxScheduler(context: SealedLightContext): OutboxScheduler {
        return outboxScheduler ?: synchronized(this) {
            outboxScheduler ?: LightOutboxScheduler(context).also { outboxScheduler = it }
        }
    }

    fun clientForActiveAccount(context: SealedLightContext): ZulipClient? {
        val account = globalStore(context).activeStore.value?.account ?: return null
        val apiKey = accountRepository(context).getApiKey(account.id) ?: return null
        return ZulipClient(account.realmUrl, account.email, apiKey)
    }

    fun preferences(context: SealedLightContext): ZulipPreferences {
        return preferences ?: synchronized(this) {
            preferences ?: ZulipPreferences(context.dataStore).also { preferences = it }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun startNotificationAsterisk(context: SealedLightContext) {
        if (asteriskScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        asteriskScope = scope
        val store = globalStore(context)
        scope.launch {
            combine(
                preferences(context).showNotificationAsterisk,
                store.activeStore.flatMapLatest { active ->
                    active?.unreadCounts ?: flowOf(emptyMap())
                },
                asteriskPoke,
            ) { enabled, counts, _ ->
                enabled to counts.values.any { it > 0 }
            }.collect { (enabled, activeUnread) ->
                val anyUnread = activeUnread || withContext(Dispatchers.IO) {
                    inactiveRealmHasUnread(context)
                }
                val granted = notificationPermissionGranted()
                when (notificationAsteriskAction(enabled, anyUnread, granted)) {
                    NotificationAsteriskAction.Post ->
                        context.notifications.post("Zulip", "Unread messages")
                    NotificationAsteriskAction.Clear ->
                        context.notifications.cancel()
                }
            }
        }
    }

    fun pokeNotificationAsterisk() {
        asteriskPoke.value += 1
    }

    fun requestNotificationPermissionIfNeeded(
        context: SealedLightContext,
        launch: () -> Unit,
    ) {
        if (notificationPermissionRequested) return
        notificationPermissionRequested = true
        val scope = asteriskScope ?: return
        scope.launch {
            val enabled = preferences(context).showNotificationAsterisk.first()
            if (enabled && !notificationPermissionGranted()) {
                launch()
            }
        }
    }

    private suspend fun inactiveRealmHasUnread(context: SealedLightContext): Boolean {
        val activeId = globalStore(context).activeAccount.value?.id
        return accountRepository(context).getAllAccounts().any { account ->
            account.id != activeId && cacheRepository(context).hasUnread(account.id)
        }
    }

    private suspend fun notificationPermissionGranted(): Boolean =
        checkPermission(NOTIFICATION_PERMISSION)
            .asKotlinResult
            .getOrNull()
            ?.permissionResult == LightServiceMethod.GetPermission.Result.Granted

    fun globalStore(context: SealedLightContext): GlobalStore {
        return globalStore ?: synchronized(this) {
            globalStore ?: GlobalStore(
                accountRepository = accountRepository(context),
                cacheRepository = cacheRepository(context),
                coordinator = syncCoordinator(context),
                outboxRepository = outboxRepository(context),
                outboxScheduler = outboxScheduler(context),
                deliverOutbox = { accountId ->
                    outboxProcessor(context).process(accountId)
                },
            ).also { globalStore = it }
        }
    }

    private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
}
