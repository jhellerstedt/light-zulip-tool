package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipApiException
import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.ZulipGateway
import com.jhellerstedt.zulip.api.models.GetEventsResponse
import com.jhellerstedt.zulip.api.models.GetMessagesResponse
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.SendMessageResponse
import com.jhellerstedt.zulip.api.models.StreamTopicsResponse
import com.jhellerstedt.zulip.api.models.UploadFileResponse
import com.jhellerstedt.zulip.api.models.UnreadMsgs
import com.jhellerstedt.zulip.api.models.UnreadStream
import com.jhellerstedt.zulip.api.models.ZulipEvent
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.api.models.ZulipStream
import com.jhellerstedt.zulip.api.models.ZulipTopic
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.data.CachedConversationEntity
import com.jhellerstedt.zulip.data.CachedMessageEntity
import com.jhellerstedt.zulip.data.CachedStreamEntity
import com.jhellerstedt.zulip.data.CachedTopicEntity
import com.jhellerstedt.zulip.data.CachedUserEntity
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.data.ZulipCacheDao
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.PendingUploadAttachment
import com.jhellerstedt.zulip.store.ZulipStore
import com.jhellerstedt.zulip.store.ZulipSyncCoordinator
import com.jhellerstedt.zulip.store.cacheKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZulipSyncCoordinatorTest {
    private val now = 1_000_000_000L
    private val accountId = 7L

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun hydratePublishesCacheBeforeRegisterCompletes() = runTest {
        val dao = FakeSyncCacheDao()
        seedCachedMessage(dao, Narrow.Channel(1), messageId = 1, content = "cached")
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val gateway = FakeZulipGateway()
        val coordinator = ZulipSyncCoordinator(gateway, cache)
        val store = fixtureStore()
        val blockedRegister = CompletableDeferred<RegisterQueueResponse>()
        gateway.registerDeferred = blockedRegister

        val hydration = coordinator.hydrate(store)
        val registerJob = launch { coordinator.register(store) }
        runCurrent()

        assertTrue(hydration.hasCachedState)
        assertEquals("cached", store.getMessagesFor(Narrow.Channel(1)).single().content)
        assertEquals(ConnectionState.REFRESHING, store.connectionState.value)
        registerJob.cancel()
        store.client.close()
    }

    @Test
    fun hydrateReportsNoCachedStateWhenCacheIsEmpty() = runTest {
        val dao = FakeSyncCacheDao()
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val coordinator = ZulipSyncCoordinator(FakeZulipGateway(), cache)
        val store = fixtureStore()

        val hydration = coordinator.hydrate(store)

        assertFalse(hydration.hasCachedState)
        store.client.close()
    }

    @Test
    fun failedRegisterKeepsCacheAndMarksOffline() = runTest {
        val dao = FakeSyncCacheDao()
        seedCachedMessage(dao, Narrow.Channel(1), messageId = 1, content = "cached")
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val gateway = FakeZulipGateway()
        gateway.registerFailure = ZulipApiException.Network("DNS failed")
        val coordinator = ZulipSyncCoordinator(gateway, cache)
        val store = fixtureStore()

        coordinator.hydrate(store)
        val result = coordinator.register(store)

        assertTrue(result.isFailure)
        assertEquals("cached", store.getMessagesFor(Narrow.Channel(1)).single().content)
        assertEquals(ConnectionState.OFFLINE, store.connectionState.value)
        store.client.close()
    }

    @Test
    fun registerFailureToPersistSnapshotLeavesMemoryUnchanged() = runTest {
        val dao = FakeSyncCacheDao()
        seedCachedMessage(dao, Narrow.Channel(1), messageId = 1, content = "cached")
        dao.failOnUpsertStreams = RuntimeException("db unavailable")
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val gateway = FakeZulipGateway()
        gateway.registerResponse = RegisterQueueResponse(
            result = "success",
            queueId = "new-queue",
            subscriptions = listOf(ZulipStream("general", 1, "", false)),
        )
        val coordinator = ZulipSyncCoordinator(gateway, cache)
        val store = fixtureStore()

        coordinator.hydrate(store)
        val result = coordinator.register(store)

        assertTrue(result.isFailure)
        assertEquals("", store.queueId)
        assertEquals("cached", store.getMessagesFor(Narrow.Channel(1)).single().content)
        assertEquals(ConnectionState.OFFLINE, store.connectionState.value)
        store.client.close()
    }

    @Test
    fun successfulRegisterAfterFailureClearsOfflineState() = runTest {
        val dao = FakeSyncCacheDao()
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val gateway = FakeZulipGateway()
        gateway.registerFailure = ZulipApiException.Network("DNS failed")
        val coordinator = ZulipSyncCoordinator(gateway, cache)
        val store = fixtureStore()

        coordinator.register(store)
        assertEquals(ConnectionState.OFFLINE, store.connectionState.value)

        gateway.registerFailure = null
        gateway.registerResponse = RegisterQueueResponse(result = "success", queueId = "queue-1")
        val result = coordinator.register(store)

        assertTrue(result.isSuccess)
        assertEquals(ConnectionState.ONLINE, store.connectionState.value)
        store.client.close()
    }

    @Test
    fun registerAgainRecoversFromStaleQueueWithFreshQueueId() = runTest {
        val dao = FakeSyncCacheDao()
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val gateway = FakeZulipGateway()
        gateway.registerResponse = RegisterQueueResponse(result = "success", queueId = "queue-1")
        val coordinator = ZulipSyncCoordinator(gateway, cache)
        val store = fixtureStore()

        coordinator.register(store).getOrThrow()
        assertEquals("queue-1", store.queueId)

        gateway.registerResponse = RegisterQueueResponse(result = "success", queueId = "queue-2")
        coordinator.register(store).getOrThrow()

        assertEquals("queue-2", store.queueId)
        store.client.close()
    }

    @Test
    fun registerPersistsFeatureLevelDowngradeSoItSurvivesAccountReload() = runTest {
        val dao = FakeSyncCacheDao()
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val gateway = FakeZulipGateway()
        gateway.registerFailureOnce = com.jhellerstedt.zulip.api.ZulipApiException.ApiError(
            statusCode = 400,
            code = "BAD_REQUEST",
            message = "\"idle_queue_timeout\" is not valid JSON",
        )
        gateway.registerResponse = RegisterQueueResponse(result = "success", queueId = "queue-1")
        val persistedDowngrades = mutableListOf<StoredZulipAccount>()
        val coordinator = ZulipSyncCoordinator(
            gateway = gateway,
            cache = cache,
            onFeatureLevelDowngraded = { account -> persistedDowngrades += account },
        )
        val store = fixtureStore(fixtureAccount().copy(zulipFeatureLevel = 42))

        val result = coordinator.register(store)

        assertTrue(result.isSuccess)
        assertEquals(listOf(42, 0), gateway.registerFeatureLevels)
        assertEquals(listOf(0), persistedDowngrades.map { it.zulipFeatureLevel })
        assertEquals(0, store.account.zulipFeatureLevel)
        store.client.close()
    }

    @Test
    fun applyEventPersistFailureLeavesMemoryUnchanged() = runTest {
        val dao = FakeSyncCacheDao()
        dao.failOnUpsertMessages = RuntimeException("db unavailable")
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val coordinator = ZulipSyncCoordinator(FakeZulipGateway(), cache)
        val store = fixtureStore()
        store.applyRegisterResponse(
            RegisterQueueResponse(
                result = "success",
                queueId = "queue-1",
                subscriptions = listOf(ZulipStream("general", 1, "", false)),
            ),
        )
        val event = ZulipEvent(
            type = "message",
            id = 5,
            message = ZulipMessage(
                id = 42,
                type = "stream",
                content = "hello there",
                streamId = 1,
                topic = "release",
                timestamp = now,
            ),
        )

        val error = runCatching { coordinator.applyEvent(store, event) }.exceptionOrNull()

        assertTrue(error is RuntimeException)
        assertTrue(store.getMessagesFor(Narrow.Topic(1, "release")).isEmpty())
        assertEquals(-1, store.lastEventId)
        store.client.close()
    }

    @Test
    fun applyEventPersistsMessageToCacheAndMemory() = runTest {
        val dao = FakeSyncCacheDao()
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val coordinator = ZulipSyncCoordinator(FakeZulipGateway(), cache)
        val store = fixtureStore()
        store.applyRegisterResponse(
            RegisterQueueResponse(
                result = "success",
                queueId = "queue-1",
                subscriptions = listOf(ZulipStream("general", 1, "", false)),
            ),
        )
        val event = ZulipEvent(
            type = "message",
            id = 5,
            message = ZulipMessage(
                id = 42,
                type = "stream",
                content = "hello there",
                streamId = 1,
                topic = "release",
                timestamp = now,
            ),
        )

        coordinator.applyEvent(store, event)

        assertEquals("hello there", store.getMessagesFor(Narrow.Topic(1, "release")).single().content)
        val cachedRow = dao.messageRows.single { it.messageId == 42 }
        assertEquals(Narrow.Topic(1, "release").cacheKey(), cachedRow.narrowKey)
        assertTrue(cachedRow.payloadJson.contains("hello there"))
        store.client.close()
    }

    @Test
    fun refreshMessagesPersistsSortedMessagesAndUpdatesStore() = runTest {
        val dao = FakeSyncCacheDao()
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val gateway = FakeZulipGateway()
        gateway.messagesResponse = GetMessagesResponse(
            result = "success",
            messages = listOf(
                ZulipMessage(id = 9, type = "stream", content = "nine", streamId = 1, topic = "x"),
                ZulipMessage(id = 3, type = "stream", content = "three", streamId = 1, topic = "x"),
            ),
        )
        val coordinator = ZulipSyncCoordinator(gateway, cache)
        val store = fixtureStore()
        val narrow = Narrow.Topic(1, "x")

        val result = coordinator.refreshMessages(store, narrow)

        assertEquals(listOf(3, 9), result.getOrThrow().map { it.id })
        assertEquals(listOf(3, 9), store.getMessagesFor(narrow).map { it.id })
        assertEquals(listOf(3, 9), cache.messages(accountId, narrow).map { it.id })
        store.client.close()
    }

    @Test
    fun cachedTopicsReturnsPersistedTopicsAndRefreshTopicsReplacesThem() = runTest {
        val dao = FakeSyncCacheDao()
        dao.topicRows += CachedTopicEntity(accountId, 1, "stale", 1, now)
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val gateway = FakeZulipGateway()
        gateway.topicsResponse = StreamTopicsResponse(
            result = "success",
            topics = listOf(ZulipTopic("release", 12)),
        )
        val coordinator = ZulipSyncCoordinator(gateway, cache)
        val store = fixtureStore()

        val cached = coordinator.cachedTopics(store, 1)
        assertEquals(listOf("stale"), cached.map { it.name })

        val refreshed = coordinator.refreshTopics(store, 1)

        assertEquals(listOf("release"), refreshed.getOrThrow().map { it.name })
        assertEquals(listOf("release"), coordinator.cachedTopics(store, 1).map { it.name })
        store.client.close()
    }

    private fun fixtureStore(account: StoredZulipAccount = fixtureAccount()): ZulipStore = ZulipStore(
        account = account,
        client = ZulipClient(account.realmUrl, account.email, "api-key"),
    )

    private fun fixtureAccount(id: Long = accountId): StoredZulipAccount = StoredZulipAccount(
        id = id,
        realmUrl = "https://example.com",
        realmName = "Example",
        email = "user@example.com",
        userId = 1,
        zulipFeatureLevel = 0,
    )

    private fun seedCachedMessage(
        dao: FakeSyncCacheDao,
        narrow: Narrow,
        messageId: Int,
        content: String,
    ) {
        dao.conversationRows += CachedConversationEntity(
            accountId = accountId,
            narrowKey = narrow.cacheKey(),
            title = "title",
            unreadCount = 1,
            lastMessageId = messageId,
            lastMessageTimestamp = now,
            updatedAt = now,
        )
        dao.messageRows += CachedMessageEntity(
            accountId = accountId,
            messageId = messageId,
            narrowKey = narrow.cacheKey(),
            payloadJson = Json.encodeToString(
                ZulipMessage(
                    id = messageId,
                    type = "stream",
                    content = content,
                    streamId = 1,
                    topic = "release",
                    timestamp = now,
                ),
            ),
            cachedAt = now,
        )
    }
}

private class FakeZulipGateway : ZulipGateway {
    var registerDeferred: CompletableDeferred<RegisterQueueResponse>? = null
    var registerResponse: RegisterQueueResponse? = null
    var registerFailure: Throwable? = null
    var registerFailureOnce: Throwable? = null
    val registerFeatureLevels = mutableListOf<Int>()

    var messagesResponse: GetMessagesResponse? = null
    var topicsResponse: StreamTopicsResponse? = null

    override suspend fun register(client: ZulipClient, featureLevel: Int): RegisterQueueResponse {
        registerFeatureLevels += featureLevel
        registerDeferred?.let { return it.await() }
        registerFailureOnce?.let { failure ->
            registerFailureOnce = null
            throw failure
        }
        registerFailure?.let { throw it }
        return registerResponse ?: error("no register response configured")
    }

    override suspend fun events(
        client: ZulipClient,
        queueId: String,
        lastEventId: Int,
        timeoutMillis: Long,
        dontBlock: Boolean,
    ): GetEventsResponse = throw UnsupportedOperationException("not used in these tests")

    override suspend fun messages(client: ZulipClient, narrow: Narrow): GetMessagesResponse =
        messagesResponse ?: error("no messages response configured")

    override suspend fun topics(client: ZulipClient, streamId: Int): StreamTopicsResponse =
        topicsResponse ?: error("no topics response configured")

    override suspend fun upload(client: ZulipClient, attachment: PendingUploadAttachment): UploadFileResponse =
        throw UnsupportedOperationException("not used in these tests")

    override suspend fun send(client: ZulipClient, narrow: Narrow, content: String): SendMessageResponse =
        throw UnsupportedOperationException("not used in these tests")
}

private class FakeSyncCacheDao : ZulipCacheDao {
    val streamRows = mutableListOf<CachedStreamEntity>()
    val userRows = mutableListOf<CachedUserEntity>()
    val topicRows = mutableListOf<CachedTopicEntity>()
    val conversationRows = mutableListOf<CachedConversationEntity>()
    val messageRows = mutableListOf<CachedMessageEntity>()

    var failOnUpsertStreams: Throwable? = null
    var failOnUpsertMessages: Throwable? = null

    override suspend fun streams(accountId: Long): List<CachedStreamEntity> =
        streamRows.filter { it.accountId == accountId }

    override suspend fun users(accountId: Long): List<CachedUserEntity> =
        userRows.filter { it.accountId == accountId }

    override suspend fun userById(accountId: Long, userId: Int): CachedUserEntity? =
        userRows.firstOrNull { it.accountId == accountId && it.userId == userId }

    override suspend fun topics(accountId: Long, streamId: Int): List<CachedTopicEntity> =
        topicRows.filter { it.accountId == accountId && it.streamId == streamId }

    override suspend fun conversations(accountId: Long): List<CachedConversationEntity> =
        conversationRows.filter { it.accountId == accountId }

    override suspend fun messages(accountId: Long, narrowKey: String, limit: Int): List<CachedMessageEntity> =
        messageRows
            .filter { it.accountId == accountId && it.narrowKey == narrowKey }
            .sortedByDescending { it.messageId }
            .take(limit)

    override suspend fun messageNarrowKeys(accountId: Long): List<String> =
        messageRows.filter { it.accountId == accountId }.map { it.narrowKey }.distinct()

    override suspend fun messageById(accountId: Long, messageId: Int): CachedMessageEntity? =
        messageRows.firstOrNull { it.accountId == accountId && it.messageId == messageId }

    override suspend fun upsertStreams(rows: List<CachedStreamEntity>) {
        failOnUpsertStreams?.let { throw it }
        rows.forEach { row ->
            streamRows.removeAll { it.accountId == row.accountId && it.streamId == row.streamId }
            streamRows += row
        }
    }

    override suspend fun upsertUsers(rows: List<CachedUserEntity>) {
        rows.forEach { row ->
            userRows.removeAll { it.accountId == row.accountId && it.userId == row.userId }
            userRows += row
        }
    }

    override suspend fun upsertTopics(rows: List<CachedTopicEntity>) {
        rows.forEach { row ->
            topicRows.removeAll {
                it.accountId == row.accountId && it.streamId == row.streamId && it.topicName == row.topicName
            }
            topicRows += row
        }
    }

    override suspend fun upsertConversations(rows: List<CachedConversationEntity>) {
        rows.forEach { row ->
            conversationRows.removeAll { it.accountId == row.accountId && it.narrowKey == row.narrowKey }
            conversationRows += row
        }
    }

    override suspend fun upsertMessages(rows: List<CachedMessageEntity>) {
        failOnUpsertMessages?.let { throw it }
        rows.forEach { row ->
            messageRows.removeAll {
                it.accountId == row.accountId && it.narrowKey == row.narrowKey && it.messageId == row.messageId
            }
            messageRows += row
        }
    }

    override suspend fun deleteStreams(accountId: Long) {
        streamRows.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteUsers(accountId: Long) {
        userRows.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteTopics(accountId: Long, streamId: Int) {
        topicRows.removeAll { it.accountId == accountId && it.streamId == streamId }
    }

    override suspend fun deleteConversations(accountId: Long) {
        conversationRows.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteStream(accountId: Long, streamId: Int) {
        streamRows.removeAll { it.accountId == accountId && it.streamId == streamId }
    }

    override suspend fun deleteUser(accountId: Long, userId: Int) {
        userRows.removeAll { it.accountId == accountId && it.userId == userId }
    }

    override suspend fun updateConversationUnreadCount(accountId: Long, narrowKey: String, unreadCount: Int) {
        val index = conversationRows.indexOfFirst { it.accountId == accountId && it.narrowKey == narrowKey }
        if (index >= 0) {
            conversationRows[index] = conversationRows[index].copy(unreadCount = unreadCount)
        }
    }

    override suspend fun deleteMessages(accountId: Long, narrowKey: String) {
        messageRows.removeAll { it.accountId == accountId && it.narrowKey == narrowKey }
    }

    override suspend fun deleteMessagesOlderThan(accountId: Long, cutoff: Long) {
        messageRows.removeAll { it.accountId == accountId && it.cachedAt < cutoff }
    }

    override suspend fun deleteMessagesExcept(accountId: Long, narrowKey: String, keepIds: List<Int>) {
        messageRows.removeAll {
            it.accountId == accountId && it.narrowKey == narrowKey && it.messageId !in keepIds
        }
    }

    override suspend fun deleteTopicsForAccount(accountId: Long) {
        topicRows.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteMessagesForAccount(accountId: Long) {
        messageRows.removeAll { it.accountId == accountId }
    }
}
