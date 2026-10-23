package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.ZulipGateway
import com.jhellerstedt.zulip.api.models.GetEventsResponse
import com.jhellerstedt.zulip.api.models.GetMessagesResponse
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.SendMessageResponse
import com.jhellerstedt.zulip.api.models.StreamTopicsResponse
import com.jhellerstedt.zulip.api.models.UploadFileResponse
import com.jhellerstedt.zulip.data.OutboxFileStore
import com.jhellerstedt.zulip.data.OutboxMessageState
import com.jhellerstedt.zulip.data.PendingAttachmentEntity
import com.jhellerstedt.zulip.data.PendingMessageEntity
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.data.ZulipAccountStore
import com.jhellerstedt.zulip.data.ZulipCacheDao
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.data.ZulipOutboxDao
import com.jhellerstedt.zulip.data.ZulipOutboxRepository
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.OutboxScheduler
import com.jhellerstedt.zulip.store.PendingUploadAttachment
import com.jhellerstedt.zulip.ui.ZulipHomeViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ZulipHomeViewModelTest {
    private val now = 1_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pendingCountFollowsActiveAccount() = runTest {
        val outboxDao = InMemoryOutboxDao()
        val globalStore = buildGlobalStore(outboxDao, scope = backgroundScope)
        outboxDao.seedQueuedMessage(accountId = 1L, localId = "m1")

        globalStore.switchAccount(1L).getOrThrow()
        val viewModel = ZulipHomeViewModel(globalStore)

        val firstState = withTimeout(1_000) {
            viewModel.uiState.first { it.pendingMessageCount == 1 }
        }
        assertEquals(1, firstState.pendingMessageCount)

        globalStore.switchAccount(2L).getOrThrow()

        val secondState = withTimeout(1_000) {
            viewModel.uiState.first { it.pendingMessageCount == 0 }
        }
        assertEquals(0, secondState.pendingMessageCount)
    }

    @Test
    fun retryFailedMessageSchedulesOutboxWorker() = runTest {
        val outboxDao = InMemoryOutboxDao()
        val callLog = mutableListOf<String>()
        val globalStore = buildGlobalStore(outboxDao, scope = backgroundScope, callLog = callLog)
        outboxDao.seedFailedMessage(accountId = 1L, localId = "m1")

        globalStore.switchAccount(1L).getOrThrow()
        val viewModel = ZulipHomeViewModel(globalStore)

        val failedState = withTimeout(1_000) {
            viewModel.uiState.first { it.failedMessage != null }
        }
        val failedMessage = assertNotNull(failedState.failedMessage)
        assertEquals("m1", failedMessage.localId)

        viewModel.retryFailedMessage()

        val recoveredState = withTimeout(1_000) {
            viewModel.uiState.first { it.failedMessage == null }
        }
        assertNull(recoveredState.failedMessage)
        assertTrue(callLog.contains("scheduler.enqueue"))
    }

    @Test
    fun retryFailedMessageSucceedsWhenSchedulerThrows() = runTest {
        val outboxDao = InMemoryOutboxDao()
        val callLog = mutableListOf<String>()
        val globalStore = buildGlobalStore(
            outboxDao,
            scope = backgroundScope,
            callLog = callLog,
            scheduler = ThrowingHomeOutboxScheduler(callLog),
        )
        outboxDao.seedFailedMessage(accountId = 1L, localId = "m1")

        globalStore.switchAccount(1L).getOrThrow()
        val viewModel = ZulipHomeViewModel(globalStore)

        withTimeout(1_000) {
            viewModel.uiState.first { it.failedMessage != null }
        }

        viewModel.retryFailedMessage()

        val recoveredState = withTimeout(1_000) {
            viewModel.uiState.first { it.failedMessage == null }
        }
        assertNull(recoveredState.failedMessage)
        assertEquals(com.jhellerstedt.zulip.data.OutboxMessageState.QUEUED, outboxDao.message("m1")?.state)
        assertTrue(callLog.contains("scheduler.enqueue"))
    }

    @Test
    fun discardFailedMessageRemovesFailure() = runTest {
        val outboxDao = InMemoryOutboxDao()
        val globalStore = buildGlobalStore(outboxDao, scope = backgroundScope)
        outboxDao.seedFailedMessage(accountId = 1L, localId = "m1")

        globalStore.switchAccount(1L).getOrThrow()
        val viewModel = ZulipHomeViewModel(globalStore)

        withTimeout(1_000) {
            viewModel.uiState.first { it.failedMessage != null }
        }

        viewModel.discardFailedMessage()

        val state = withTimeout(1_000) {
            viewModel.uiState.first { it.failedMessage == null }
        }
        assertNull(state.failedMessage)
        assertNull(outboxDao.message("m1"))
    }

    @Test
    fun closeFailedMessageHidesOverlayWithoutMutatingOutboxState() = runTest {
        val outboxDao = InMemoryOutboxDao()
        val globalStore = buildGlobalStore(outboxDao, scope = backgroundScope)
        outboxDao.seedFailedMessage(accountId = 1L, localId = "m1")

        globalStore.switchAccount(1L).getOrThrow()
        val viewModel = ZulipHomeViewModel(globalStore)

        withTimeout(1_000) {
            viewModel.uiState.first { it.failedMessage != null }
        }

        viewModel.closeFailedMessage()

        val state = withTimeout(1_000) {
            viewModel.uiState.first { it.failedMessage == null }
        }
        assertNull(state.failedMessage)
        assertNotNull(outboxDao.message("m1"))
        assertEquals(OutboxMessageState.FAILED, outboxDao.message("m1")?.state)
    }

    private fun buildGlobalStore(
        outboxDao: ZulipOutboxDao,
        scope: kotlinx.coroutines.CoroutineScope,
        callLog: MutableList<String> = mutableListOf(),
        scheduler: OutboxScheduler = LoggingHomeOutboxScheduler(callLog),
    ): GlobalStore {
        val accountStore = FakeHomeAccountStore(
            listOf(
                fixtureAccount(id = 1L, email = "a@example.com"),
                fixtureAccount(id = 2L, email = "b@example.com"),
            ),
        )
        val cache = ZulipCacheRepository(FakeHomeCacheDao(), nowMillis = { now })
        val outboxRepository = ZulipOutboxRepository(outboxDao, NoOpHomeOutboxFileStore())
        val outboxScheduler = scheduler
        return GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = FakeHomeGateway(),
            outboxRepository = outboxRepository,
            outboxScheduler = outboxScheduler,
            scope = scope,
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
}

private class ThrowingHomeOutboxScheduler(
    private val callLog: MutableList<String>,
) : OutboxScheduler {
    override fun enqueue(accountId: Long): Boolean {
        callLog += "scheduler.enqueue"
        error("WorkManager unavailable")
    }

    override fun cancel(accountId: Long) {
        callLog += "scheduler.cancel"
    }
}

private class LoggingHomeOutboxScheduler(
    private val callLog: MutableList<String>,
) : OutboxScheduler {
    override fun enqueue(accountId: Long): Boolean {
        callLog += "scheduler.enqueue"
        return true
    }

    override fun cancel(accountId: Long) {
        callLog += "scheduler.cancel"
    }
}

private class NoOpHomeOutboxFileStore : OutboxFileStore {
    override fun copyIn(accountId: Long, messageId: String, attachmentId: String, source: File): File =
        throw UnsupportedOperationException()

    override fun delete(file: File) = Unit

    override fun deleteAccount(accountId: Long) = Unit
}

private class FakeHomeGateway : ZulipGateway {
    override suspend fun register(client: ZulipClient, featureLevel: Int): RegisterQueueResponse =
        RegisterQueueResponse(result = "success", queueId = "queue-default")

    override suspend fun events(
        client: ZulipClient,
        queueId: String,
        lastEventId: Int,
        timeoutMillis: Long,
        dontBlock: Boolean,
    ): GetEventsResponse = throw RuntimeException("stop-loop")

    override suspend fun messages(client: ZulipClient, narrow: Narrow): GetMessagesResponse =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun topics(client: ZulipClient, streamId: Int): StreamTopicsResponse =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun upload(client: ZulipClient, attachment: PendingUploadAttachment): UploadFileResponse =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun send(client: ZulipClient, narrow: Narrow, content: String): SendMessageResponse =
        throw UnsupportedOperationException("not used in this test")
}

private class FakeHomeAccountStore(accounts: List<StoredZulipAccount>) : ZulipAccountStore {
    private val accountsById = accounts.associateByTo(LinkedHashMap()) { it.id }
    private var activeId: Long? = null

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
    ): StoredZulipAccount = error("not used in this test")

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

/**
 * A minimal in-memory outbox DAO that keeps its pending/failure flows reactive to
 * mutation, matching Room's `Flow`-returning `@Query` behavior closely enough for
 * [ZulipHomeViewModel] to observe state transitions end-to-end.
 */
private class InMemoryOutboxDao : ZulipOutboxDao {
    private val messagesFlow = MutableStateFlow<Map<String, PendingMessageEntity>>(emptyMap())
    private val attachmentsByMessage = mutableMapOf<String, MutableList<PendingAttachmentEntity>>()

    override suspend fun insertMessage(message: PendingMessageEntity) {
        messagesFlow.update { it + (message.localId to message) }
    }

    override suspend fun insertAttachments(attachments: List<PendingAttachmentEntity>) {
        attachments.forEach { attachmentsByMessage.getOrPut(it.messageLocalId) { mutableListOf() } += it }
    }

    override fun pendingCount(accountId: Long): Flow<Int> = messagesFlow.map { messages ->
        messages.values.count {
            it.accountId == accountId && it.state != OutboxMessageState.FAILED
        }
    }

    override fun firstFailedMessage(accountId: Long): Flow<PendingMessageEntity?> = messagesFlow.map { messages ->
        messages.values
            .filter { it.accountId == accountId && it.state == OutboxMessageState.FAILED }
            .minByOrNull { it.createdAt }
    }

    override suspend fun oldestQueuedMessage(accountId: Long): PendingMessageEntity? =
        messagesFlow.value.values
            .filter { it.accountId == accountId && it.state == OutboxMessageState.QUEUED }
            .minByOrNull { it.createdAt }

    override suspend fun attachments(accountId: Long, messageLocalId: String): List<PendingAttachmentEntity> =
        attachmentsByMessage[messageLocalId]?.filter { it.accountId == accountId }.orEmpty()

    override suspend fun message(messageLocalId: String): PendingMessageEntity? = messagesFlow.value[messageLocalId]

    override suspend fun attachment(attachmentLocalId: String): PendingAttachmentEntity? =
        attachmentsByMessage.values.flatten().firstOrNull { it.localId == attachmentLocalId }

    override suspend fun markSending(accountId: Long, messageLocalId: String, attemptAt: Long) {
        updateState(messageLocalId) { it.copy(state = OutboxMessageState.SENDING, attemptAt = attemptAt) }
    }

    override suspend fun markQueued(accountId: Long, messageLocalId: String) {
        updateState(messageLocalId) { it.copy(state = OutboxMessageState.QUEUED, failureReason = null) }
    }

    override suspend fun markFailed(accountId: Long, messageLocalId: String, failureReason: String) {
        updateState(messageLocalId) { it.copy(state = OutboxMessageState.FAILED, failureReason = failureReason) }
    }

    override suspend fun checkpointUpload(accountId: Long, attachmentLocalId: String, uploadedUrl: String) = Unit

    override suspend fun deleteMessage(accountId: Long, messageLocalId: String) {
        messagesFlow.update { it - messageLocalId }
        attachmentsByMessage.remove(messageLocalId)
    }

    override suspend fun resetSending(accountId: Long) = Unit

    override suspend fun deleteMessagesForAccount(accountId: Long) {
        messagesFlow.update { messages -> messages.filterValues { it.accountId != accountId } }
    }

    fun seedQueuedMessage(accountId: Long, localId: String) {
        messagesFlow.update {
            it + (
                localId to PendingMessageEntity(
                    localId = localId,
                    accountId = accountId,
                    narrowKey = "stream:1:",
                    content = "hi",
                    state = OutboxMessageState.QUEUED,
                    createdAt = 1L,
                    attemptAt = null,
                    failureReason = null,
                )
                )
        }
    }

    fun seedFailedMessage(accountId: Long, localId: String) {
        messagesFlow.update {
            it + (
                localId to PendingMessageEntity(
                    localId = localId,
                    accountId = accountId,
                    narrowKey = "stream:1:",
                    content = "hi",
                    state = OutboxMessageState.FAILED,
                    createdAt = 1L,
                    attemptAt = 1L,
                    failureReason = "boom",
                )
                )
        }
    }

    private fun updateState(messageLocalId: String, transform: (PendingMessageEntity) -> PendingMessageEntity) {
        messagesFlow.update { messages -> messages[messageLocalId]?.let { messages + (messageLocalId to transform(it)) } ?: messages }
    }
}

private class FakeHomeCacheDao : ZulipCacheDao {
    private val streamRows = mutableListOf<com.jhellerstedt.zulip.data.CachedStreamEntity>()
    private val userRows = mutableListOf<com.jhellerstedt.zulip.data.CachedUserEntity>()
    private val topicRows = mutableListOf<com.jhellerstedt.zulip.data.CachedTopicEntity>()
    private val conversationRows = mutableListOf<com.jhellerstedt.zulip.data.CachedConversationEntity>()
    private val messageRows = mutableListOf<com.jhellerstedt.zulip.data.CachedMessageEntity>()

    override suspend fun streams(accountId: Long) = streamRows.filter { it.accountId == accountId }
    override suspend fun users(accountId: Long) = userRows.filter { it.accountId == accountId }
    override suspend fun userById(accountId: Long, userId: Int) =
        userRows.firstOrNull { it.accountId == accountId && it.userId == userId }
    override suspend fun topics(accountId: Long, streamId: Int) =
        topicRows.filter { it.accountId == accountId && it.streamId == streamId }
    override suspend fun conversations(accountId: Long) = conversationRows.filter { it.accountId == accountId }
    override suspend fun messages(accountId: Long, narrowKey: String, limit: Int) =
        messageRows.filter { it.accountId == accountId && it.narrowKey == narrowKey }
            .sortedByDescending { it.messageId }.take(limit)
    override suspend fun messageNarrowKeys(accountId: Long) =
        messageRows.filter { it.accountId == accountId }.map { it.narrowKey }.distinct()
    override suspend fun messageById(accountId: Long, messageId: Int) =
        messageRows.firstOrNull { it.accountId == accountId && it.messageId == messageId }
    override suspend fun upsertStreams(rows: List<com.jhellerstedt.zulip.data.CachedStreamEntity>) {
        rows.forEach { row ->
            streamRows.removeAll { it.accountId == row.accountId && it.streamId == row.streamId }
            streamRows += row
        }
    }
    override suspend fun upsertUsers(rows: List<com.jhellerstedt.zulip.data.CachedUserEntity>) {
        rows.forEach { row ->
            userRows.removeAll { it.accountId == row.accountId && it.userId == row.userId }
            userRows += row
        }
    }
    override suspend fun upsertTopics(rows: List<com.jhellerstedt.zulip.data.CachedTopicEntity>) {
        rows.forEach { row ->
            topicRows.removeAll { it.accountId == row.accountId && it.streamId == row.streamId && it.topicName == row.topicName }
            topicRows += row
        }
    }
    override suspend fun upsertConversations(rows: List<com.jhellerstedt.zulip.data.CachedConversationEntity>) {
        rows.forEach { row ->
            conversationRows.removeAll { it.accountId == row.accountId && it.narrowKey == row.narrowKey }
            conversationRows += row
        }
    }
    override suspend fun upsertMessages(rows: List<com.jhellerstedt.zulip.data.CachedMessageEntity>) {
        rows.forEach { row ->
            messageRows.removeAll { it.accountId == row.accountId && it.narrowKey == row.narrowKey && it.messageId == row.messageId }
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
        if (index >= 0) conversationRows[index] = conversationRows[index].copy(unreadCount = unreadCount)
    }
    override suspend fun deleteMessages(accountId: Long, narrowKey: String) {
        messageRows.removeAll { it.accountId == accountId && it.narrowKey == narrowKey }
    }
    override suspend fun deleteMessagesOlderThan(accountId: Long, cutoff: Long) {
        messageRows.removeAll { it.accountId == accountId && it.cachedAt < cutoff }
    }
    override suspend fun deleteMessagesExcept(accountId: Long, narrowKey: String, keepIds: List<Int>) {
        messageRows.removeAll { it.accountId == accountId && it.narrowKey == narrowKey && it.messageId !in keepIds }
    }
    override suspend fun deleteTopicsForAccount(accountId: Long) {
        topicRows.removeAll { it.accountId == accountId }
    }
    override suspend fun deleteMessagesForAccount(accountId: Long) {
        messageRows.removeAll { it.accountId == accountId }
    }
    override suspend fun deleteAccount(accountId: Long) {
        deleteStreams(accountId)
        deleteUsers(accountId)
        deleteTopicsForAccount(accountId)
        deleteConversations(accountId)
        deleteMessagesForAccount(accountId)
    }
}
