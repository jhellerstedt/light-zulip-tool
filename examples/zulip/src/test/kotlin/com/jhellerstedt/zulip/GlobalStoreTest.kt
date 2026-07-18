package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.ZulipGateway
import com.jhellerstedt.zulip.api.models.GetEventsResponse
import com.jhellerstedt.zulip.api.models.GetMessagesResponse
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.SendMessageResponse
import com.jhellerstedt.zulip.api.models.StreamTopicsResponse
import com.jhellerstedt.zulip.api.models.UploadFileResponse
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.data.CachedConversationEntity
import com.jhellerstedt.zulip.data.CachedMessageEntity
import com.jhellerstedt.zulip.data.CachedStreamEntity
import com.jhellerstedt.zulip.data.CachedTopicEntity
import com.jhellerstedt.zulip.data.CachedUserEntity
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.data.ZulipAccountStore
import com.jhellerstedt.zulip.data.OutboxFileStore
import com.jhellerstedt.zulip.data.PendingAttachmentEntity
import com.jhellerstedt.zulip.data.PendingMessageEntity
import com.jhellerstedt.zulip.data.ZulipCacheDao
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.data.ZulipOutboxDao
import com.jhellerstedt.zulip.data.ZulipOutboxRepository
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.OutboxScheduler
import com.jhellerstedt.zulip.store.PendingUploadAttachment
import com.jhellerstedt.zulip.store.cacheKey
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the account-switch/background-register race: a cached account's
 * `coordinator.register` runs in the background (see [GlobalStore]'s `switchAccountLocked`)
 * and must never be allowed to (re)start the poller for a store that is no longer active.
 */
class GlobalStoreTest {
    private val now = 1_000_000_000L

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun staleBackgroundRegisterCompletingAfterAnAccountSwitchNeverRestartsThePollerOnTheOldAccount() = runTest {
        val dao = FakeGlobalCacheDao()
        seedCachedConversation(dao, accountId = 1L, narrow = Narrow.Channel(10))
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val gateway = FakeGlobalGateway()
        val accountStore = FakeAccountStore(
            listOf(
                fixtureAccount(id = 1L, email = "a@example.com"),
                fixtureAccount(id = 2L, email = "b@example.com"),
            ),
        )
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = gateway,
            scope = backgroundScope,
        )

        // Account 1 has cached state, so switching to it hydrates from cache immediately
        // and registers in the *background* (fire-and-forget). Block that register call so
        // it only resolves later, after we've already switched away.
        gateway.blockOnCall = 1
        val storeA = globalStore.switchAccount(1L).getOrThrow()
        runCurrent()
        assertEquals(1, gateway.registerCallCount)
        assertTrue(gateway.eventsCalls.isEmpty())

        // Account 2 has no cached state, so switching to it registers synchronously and
        // starts the poller for it right away.
        gateway.nextRegisterResponse = RegisterQueueResponse(result = "success", queueId = "queue-b")
        val storeB = globalStore.switchAccount(2L).getOrThrow()
        runCurrent()
        assertEquals(listOf("queue-b"), gateway.eventsCalls)

        // Now let account 1's stale background register finally complete successfully.
        gateway.blockedRegister.complete(RegisterQueueResponse(result = "success", queueId = "queue-a"))
        runCurrent()

        // The register did complete (proving this isn't a no-op/cancelled job)...
        assertEquals("queue-a", storeA.queueId)
        // ...but the poller must still be polling account 2's queue; it must never have been
        // restarted against the stale account-1 store.
        assertTrue(gateway.eventsCalls.none { it == "queue-a" })
        assertEquals(listOf("queue-b"), gateway.eventsCalls)

        storeA.client.close()
        storeB.client.close()
    }

    @Test
    fun enqueueMessagePersistsThenSchedulesOutboxWorker() = runTest {
        val accountId = 1L
        val callLog = mutableListOf<String>()
        val accountStore = FakeAccountStore(listOf(fixtureAccount(id = accountId, email = "a@example.com")))
        val cache = ZulipCacheRepository(FakeGlobalCacheDao(), nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(RecordingOutboxDao(callLog), NoOpOutboxFileStore())
        val outboxScheduler = LoggingOutboxScheduler(callLog, enqueueResult = true)
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeGlobalGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = backgroundScope,
        )

        val result = globalStore.enqueueMessage(
            accountId = accountId,
            narrow = Narrow.Channel(10),
            content = "hello",
            attachments = emptyList(),
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("outbox.enqueue", "scheduler.enqueue"), callLog)
    }

    @Test
    fun enqueueMessageSucceedsWhenSchedulerRejectsWork() = runTest {
        val accountId = 1L
        val callLog = mutableListOf<String>()
        val accountStore = FakeAccountStore(listOf(fixtureAccount(id = accountId, email = "a@example.com")))
        val cache = ZulipCacheRepository(FakeGlobalCacheDao(), nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(RecordingOutboxDao(callLog), NoOpOutboxFileStore())
        val outboxScheduler = LoggingOutboxScheduler(callLog, enqueueResult = false)
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeGlobalGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = backgroundScope,
        )

        val result = globalStore.enqueueMessage(
            accountId = accountId,
            narrow = Narrow.Channel(10),
            content = "hello",
            attachments = emptyList(),
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("outbox.enqueue", "scheduler.enqueue"), callLog)
    }

    @Test
    fun enqueueMessageSucceedsWhenSchedulerThrows() = runTest {
        val accountId = 1L
        val callLog = mutableListOf<String>()
        val accountStore = FakeAccountStore(listOf(fixtureAccount(id = accountId, email = "a@example.com")))
        val cache = ZulipCacheRepository(FakeGlobalCacheDao(), nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(RecordingOutboxDao(callLog), NoOpOutboxFileStore())
        val outboxScheduler = ThrowingOutboxScheduler(callLog)
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeGlobalGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = backgroundScope,
        )

        val result = globalStore.enqueueMessage(
            accountId = accountId,
            narrow = Narrow.Channel(10),
            content = "hello",
            attachments = emptyList(),
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("outbox.enqueue", "scheduler.enqueue"), callLog)
    }

    @Test
    fun enqueueMessagePropagatesLocalPersistenceFailureWithoutScheduling() = runTest {
        val accountId = 1L
        val callLog = mutableListOf<String>()
        val accountStore = FakeAccountStore(listOf(fixtureAccount(id = accountId, email = "a@example.com")))
        val cache = ZulipCacheRepository(FakeGlobalCacheDao(), nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(FailingInsertOutboxDao(callLog), NoOpOutboxFileStore())
        val outboxScheduler = LoggingOutboxScheduler(callLog, enqueueResult = true)
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeGlobalGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = backgroundScope,
        )

        val result = globalStore.enqueueMessage(
            accountId = accountId,
            narrow = Narrow.Channel(10),
            content = "hello",
            attachments = emptyList(),
        )

        assertTrue(result.isFailure)
        assertEquals(listOf("outbox.enqueue"), callLog)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchAccountSchedulesOutboxWorkerWhenPendingRowsExist() = runTest {
        val accountId = 1L
        val callLog = mutableListOf<String>()
        val accountStore = FakeAccountStore(listOf(fixtureAccount(id = accountId, email = "a@example.com")))
        val cache = ZulipCacheRepository(FakeGlobalCacheDao(), nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(RecordingOutboxDao(callLog, pendingCount = 1), NoOpOutboxFileStore())
        val outboxScheduler = LoggingOutboxScheduler(callLog, enqueueResult = true)
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeGlobalGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = backgroundScope,
        )

        globalStore.switchAccount(accountId).getOrThrow()
        runCurrent()

        assertTrue(callLog.contains("scheduler.enqueue"))
    }

    @Test
    fun retryOutboxMessageRequeuesEvenWhenSchedulerThrows() = runTest {
        val accountId = 1L
        val messageId = "msg-1"
        val callLog = mutableListOf<String>()
        val accountStore = FakeAccountStore(listOf(fixtureAccount(id = accountId, email = "a@example.com")))
        val cache = ZulipCacheRepository(FakeGlobalCacheDao(), nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(RetryRecordingOutboxDao(callLog, messageId, accountId), NoOpOutboxFileStore())
        val outboxScheduler = ThrowingOutboxScheduler(callLog)
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeGlobalGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = backgroundScope,
        )

        globalStore.retryOutboxMessage(messageId, accountId)

        assertEquals(listOf("outbox.retry", "scheduler.enqueue"), callLog)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchAccountCompletesWhenPendingRowsExistButSchedulerThrows() = runTest {
        val accountId = 1L
        val callLog = mutableListOf<String>()
        val accountStore = FakeAccountStore(listOf(fixtureAccount(id = accountId, email = "a@example.com")))
        val cache = ZulipCacheRepository(FakeGlobalCacheDao(), nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(RecordingOutboxDao(callLog, pendingCount = 1), NoOpOutboxFileStore())
        val outboxScheduler = ThrowingOutboxScheduler(callLog)
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeGlobalGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = backgroundScope,
        )

        globalStore.switchAccount(accountId).getOrThrow()
        runCurrent()

        assertEquals(listOf("scheduler.enqueue"), callLog)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchAccountDoesNotScheduleOutboxWorkerWhenNoPendingRows() = runTest {
        val accountId = 1L
        val callLog = mutableListOf<String>()
        val accountStore = FakeAccountStore(listOf(fixtureAccount(id = accountId, email = "a@example.com")))
        val cache = ZulipCacheRepository(FakeGlobalCacheDao(), nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(RecordingOutboxDao(callLog, pendingCount = 0), NoOpOutboxFileStore())
        val outboxScheduler = LoggingOutboxScheduler(callLog, enqueueResult = true)
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeGlobalGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = backgroundScope,
        )

        globalStore.switchAccount(accountId).getOrThrow()
        runCurrent()

        assertTrue(callLog.none { it == "scheduler.enqueue" })
    }

    @Test
    fun removeAccountCancelsOutboxWorkBeforeDeletingData() = runTest {
        val callLog = mutableListOf<String>()
        val accountId = 1L
        val accountStore = RecordingAccountStore(
            listOf(fixtureAccount(id = accountId, email = "a@example.com")),
            callLog,
        )
        val cacheDao = FakeGlobalCacheDao(callLog)
        val cache = ZulipCacheRepository(cacheDao, nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(RecordingOutboxDao(callLog), NoOpOutboxFileStore())
        val outboxScheduler = RecordingOutboxScheduler(callLog)
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeGlobalGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = backgroundScope,
        )

        globalStore.removeAccount(accountId)

        assertEquals(
            listOf("outbox.cancel", "outbox.delete", "cache.delete", "account.delete"),
            callLog,
        )
    }

    private fun fixtureAccount(id: Long, email: String) = StoredZulipAccount(
        id = id,
        realmUrl = "https://example.com",
        realmName = "Example",
        email = email,
        userId = 1,
        zulipFeatureLevel = 0,
    )

    private fun seedCachedConversation(dao: FakeGlobalCacheDao, accountId: Long, narrow: Narrow) {
        dao.conversationRows += CachedConversationEntity(
            accountId = accountId,
            narrowKey = narrow.cacheKey(),
            title = "title",
            unreadCount = 1,
            lastMessageId = 1,
            lastMessageTimestamp = now,
            updatedAt = now,
        )
        dao.messageRows += CachedMessageEntity(
            accountId = accountId,
            messageId = 1,
            narrowKey = narrow.cacheKey(),
            payloadJson = Json.encodeToString(
                ZulipMessage(id = 1, type = "stream", content = "hi", streamId = 10, topic = "general", timestamp = now),
            ),
            cachedAt = now,
        )
    }
}

private class RecordingAccountStore(
    accounts: List<StoredZulipAccount>,
    private val callLog: MutableList<String>,
) : FakeAccountStore(accounts) {
    override fun deleteAccount(id: Long): Boolean {
        callLog += "account.delete"
        return super.deleteAccount(id)
    }
}

private open class FakeAccountStore(accounts: List<StoredZulipAccount>) : ZulipAccountStore {
    private val accountsById = accounts.associateByTo(LinkedHashMap()) { it.id }
    private var activeId: Long? = null
    private var nextId = (accounts.maxOfOrNull { it.id } ?: 0L) + 1

    override fun getAllAccounts(): List<StoredZulipAccount> = accountsById.values.toList()

    override fun getAccount(id: Long): StoredZulipAccount? = accountsById[id]

    override fun getApiKey(id: Long): String? = if (accountsById.containsKey(id)) "api-key" else null

    override fun insertAccount(
        realmUrl: String,
        realmName: String,
        email: String,
        apiKey: String,
        userId: Int,
        zulipFeatureLevel: Int,
    ): StoredZulipAccount {
        val account = StoredZulipAccount(nextId++, realmUrl, realmName, email, userId, zulipFeatureLevel)
        accountsById[account.id] = account
        return account
    }

    override fun deleteAccount(id: Long): Boolean = accountsById.remove(id) != null

    override fun updateAccountMetadata(id: Long, realmName: String, zulipFeatureLevel: Int): StoredZulipAccount {
        val updated = requireNotNull(accountsById[id]).copy(realmName = realmName, zulipFeatureLevel = zulipFeatureLevel)
        accountsById[id] = updated
        return updated
    }

    override suspend fun getActiveAccountId(): Long? = activeId

    override suspend fun setActiveAccountId(id: Long?) {
        activeId = id
    }
}

private class FakeGlobalGateway : ZulipGateway {
    var registerCallCount = 0
    var blockOnCall = -1
    val blockedRegister = CompletableDeferred<RegisterQueueResponse>()
    var nextRegisterResponse = RegisterQueueResponse(result = "success", queueId = "queue-default")
    val eventsCalls = mutableListOf<String>()

    override suspend fun register(client: ZulipClient, featureLevel: Int): RegisterQueueResponse {
        registerCallCount += 1
        if (registerCallCount == blockOnCall) {
            return blockedRegister.await()
        }
        return nextRegisterResponse
    }

    override suspend fun events(
        client: ZulipClient,
        queueId: String,
        lastEventId: Int,
        timeoutMillis: Long,
        dontBlock: Boolean,
    ): GetEventsResponse {
        eventsCalls += queueId
        throw RuntimeException("stop-loop")
    }

    override suspend fun messages(client: ZulipClient, narrow: Narrow): GetMessagesResponse =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun topics(client: ZulipClient, streamId: Int): StreamTopicsResponse =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun upload(client: ZulipClient, attachment: PendingUploadAttachment): UploadFileResponse =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun send(client: ZulipClient, narrow: Narrow, content: String): SendMessageResponse =
        throw UnsupportedOperationException("not used in this test")
}

private class RecordingOutboxScheduler(
    private val callLog: MutableList<String>,
) : OutboxScheduler {
    override fun enqueue(accountId: Long): Boolean = true

    override fun cancel(accountId: Long) {
        callLog += "outbox.cancel"
    }
}

private class LoggingOutboxScheduler(
    private val callLog: MutableList<String>,
    private val enqueueResult: Boolean,
) : OutboxScheduler {
    override fun enqueue(accountId: Long): Boolean {
        callLog += "scheduler.enqueue"
        return enqueueResult
    }

    override fun cancel(accountId: Long) {
        callLog += "outbox.cancel"
    }
}

private class ThrowingOutboxScheduler(
    private val callLog: MutableList<String>,
) : OutboxScheduler {
    override fun enqueue(accountId: Long): Boolean {
        callLog += "scheduler.enqueue"
        error("WorkManager unavailable")
    }

    override fun cancel(accountId: Long) {
        callLog += "outbox.cancel"
    }
}

private class RetryRecordingOutboxDao(
    private val callLog: MutableList<String>,
    private val messageId: String,
    private val accountId: Long,
) : ZulipOutboxDao {
    override suspend fun insertMessage(message: PendingMessageEntity) = Unit
    override suspend fun insertAttachments(attachments: List<PendingAttachmentEntity>) = Unit
    override suspend fun insertMessageWithAttachments(
        message: PendingMessageEntity,
        attachments: List<PendingAttachmentEntity>,
    ) = Unit

    override fun pendingCount(accountId: Long): Flow<Int> = flowOf(0)
    override fun firstFailedMessage(accountId: Long): Flow<PendingMessageEntity?> = flowOf(null)
    override suspend fun oldestQueuedMessage(accountId: Long): PendingMessageEntity? = null
    override suspend fun attachments(accountId: Long, messageLocalId: String): List<PendingAttachmentEntity> = emptyList()
    override suspend fun message(messageLocalId: String): PendingMessageEntity? =
        if (messageLocalId == messageId) {
            PendingMessageEntity(
                localId = messageId,
                accountId = accountId,
                narrowKey = "stream:10:",
                content = "hello",
                state = com.jhellerstedt.zulip.data.OutboxMessageState.FAILED,
                createdAt = 1L,
                attemptAt = 1L,
                failureReason = "boom",
            )
        } else {
            null
        }

    override suspend fun attachment(attachmentLocalId: String): PendingAttachmentEntity? = null
    override suspend fun markSending(accountId: Long, messageLocalId: String, attemptAt: Long) = Unit
    override suspend fun markQueued(accountId: Long, messageLocalId: String) {
        callLog += "outbox.retry"
    }
    override suspend fun markFailed(accountId: Long, messageLocalId: String, failureReason: String) = Unit
    override suspend fun checkpointUpload(accountId: Long, attachmentLocalId: String, uploadedUrl: String) = Unit
    override suspend fun deleteMessage(accountId: Long, messageLocalId: String) = Unit
    override suspend fun resetSending(accountId: Long) = Unit
    override suspend fun deleteMessagesForAccount(accountId: Long) = Unit
}

private class RecordingOutboxDao(
    private val callLog: MutableList<String>,
    private val pendingCount: Int = 0,
) : ZulipOutboxDao {
    override suspend fun insertMessage(message: PendingMessageEntity) = Unit
    override suspend fun insertAttachments(attachments: List<PendingAttachmentEntity>) = Unit

    override suspend fun insertMessageWithAttachments(
        message: PendingMessageEntity,
        attachments: List<PendingAttachmentEntity>,
    ) {
        callLog += "outbox.enqueue"
    }

    override fun pendingCount(accountId: Long): Flow<Int> = flowOf(pendingCount)
    override fun firstFailedMessage(accountId: Long): Flow<PendingMessageEntity?> = flowOf(null)
    override suspend fun oldestQueuedMessage(accountId: Long): PendingMessageEntity? = null
    override suspend fun attachments(accountId: Long, messageLocalId: String): List<PendingAttachmentEntity> = emptyList()
    override suspend fun message(messageLocalId: String): PendingMessageEntity? = null
    override suspend fun attachment(attachmentLocalId: String): PendingAttachmentEntity? = null
    override suspend fun markSending(accountId: Long, messageLocalId: String, attemptAt: Long) = Unit
    override suspend fun markQueued(accountId: Long, messageLocalId: String) = Unit
    override suspend fun markFailed(accountId: Long, messageLocalId: String, failureReason: String) = Unit
    override suspend fun checkpointUpload(accountId: Long, attachmentLocalId: String, uploadedUrl: String) = Unit
    override suspend fun deleteMessage(accountId: Long, messageLocalId: String) = Unit
    override suspend fun resetSending(accountId: Long) = Unit

    override suspend fun deleteMessagesForAccount(accountId: Long) {
        callLog += "outbox.delete"
    }
}

private class FailingInsertOutboxDao(
    private val callLog: MutableList<String>,
) : ZulipOutboxDao {
    override suspend fun insertMessage(message: PendingMessageEntity) = Unit
    override suspend fun insertAttachments(attachments: List<PendingAttachmentEntity>) = Unit

    override suspend fun insertMessageWithAttachments(
        message: PendingMessageEntity,
        attachments: List<PendingAttachmentEntity>,
    ) {
        callLog += "outbox.enqueue"
        error("Simulated local persistence failure")
    }

    override fun pendingCount(accountId: Long): Flow<Int> = flowOf(0)
    override fun firstFailedMessage(accountId: Long): Flow<PendingMessageEntity?> = flowOf(null)
    override suspend fun oldestQueuedMessage(accountId: Long): PendingMessageEntity? = null
    override suspend fun attachments(accountId: Long, messageLocalId: String): List<PendingAttachmentEntity> = emptyList()
    override suspend fun message(messageLocalId: String): PendingMessageEntity? = null
    override suspend fun attachment(attachmentLocalId: String): PendingAttachmentEntity? = null
    override suspend fun markSending(accountId: Long, messageLocalId: String, attemptAt: Long) = Unit
    override suspend fun markQueued(accountId: Long, messageLocalId: String) = Unit
    override suspend fun markFailed(accountId: Long, messageLocalId: String, failureReason: String) = Unit
    override suspend fun checkpointUpload(accountId: Long, attachmentLocalId: String, uploadedUrl: String) = Unit
    override suspend fun deleteMessage(accountId: Long, messageLocalId: String) = Unit
    override suspend fun resetSending(accountId: Long) = Unit
    override suspend fun deleteMessagesForAccount(accountId: Long) = Unit
}

private class NoOpOutboxFileStore : OutboxFileStore {
    override fun copyIn(accountId: Long, messageId: String, attachmentId: String, source: File): File =
        throw UnsupportedOperationException()

    override fun delete(file: File) = Unit

    override fun deleteAccount(accountId: Long) = Unit
}

private class FakeGlobalCacheDao(
    private val callLog: MutableList<String>? = null,
) : ZulipCacheDao {
    val streamRows = mutableListOf<CachedStreamEntity>()
    val userRows = mutableListOf<CachedUserEntity>()
    val topicRows = mutableListOf<CachedTopicEntity>()
    val conversationRows = mutableListOf<CachedConversationEntity>()
    val messageRows = mutableListOf<CachedMessageEntity>()

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

    override suspend fun deleteAccount(accountId: Long) {
        callLog?.let { it += "cache.delete" }
        deleteStreams(accountId)
        deleteUsers(accountId)
        deleteTopicsForAccount(accountId)
        deleteConversations(accountId)
        deleteMessagesForAccount(accountId)
    }
}
