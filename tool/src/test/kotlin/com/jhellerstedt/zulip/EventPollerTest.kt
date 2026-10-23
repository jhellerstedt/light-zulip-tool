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
import com.jhellerstedt.zulip.data.CachedConversationEntity
import com.jhellerstedt.zulip.data.CachedMessageEntity
import com.jhellerstedt.zulip.data.CachedStreamEntity
import com.jhellerstedt.zulip.data.CachedTopicEntity
import com.jhellerstedt.zulip.data.CachedUserEntity
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.data.ZulipCacheDao
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.EventPoller
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.PendingUploadAttachment
import com.jhellerstedt.zulip.store.ZulipStore
import com.jhellerstedt.zulip.store.ZulipSyncCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class EventPollerTest {
    @Test
    fun prunedQueueRegistersAgainWithoutShowingAnError() = runTest {
        val hang = CompletableDeferred<GetEventsResponse>()
        val gateway = ScriptedGateway(
            eventSteps = mutableListOf(
                EventStep.Fail(
                    ZulipApiException.ApiError(
                        statusCode = 400,
                        code = "BAD_REQUEST",
                        message = "Event 9 was not in this queue",
                    ),
                ),
                EventStep.Hang(hang),
            ),
            registerResponse = RegisterQueueResponse(result = "success", queueId = "queue-new", lastEventId = 40),
        )
        val store = storeWithQueue(queueId = "queue-old", lastEventId = 9)
        val errors = mutableListOf<String?>()
        val errorsJob = launch { store.error.collect { errors += it } }
        val poller = EventPoller(this, gateway, coordinator(gateway))

        poller.start(store)
        runCurrent()

        assertEquals(listOf<String?>(null), errors)
        assertEquals("queue-new", store.queueId)
        assertEquals(40, store.lastEventId)
        assertEquals(listOf("queue-old" to 9, "queue-new" to 40), gateway.eventCalls)
        poller.stop()
        errorsJob.cancel()
        store.client.close()
    }

    @Test
    fun networkFailureGoesOfflineWithoutShowingAnError() = runTest {
        val hang = CompletableDeferred<GetEventsResponse>()
        val gateway = ScriptedGateway(
            eventSteps = mutableListOf(
                EventStep.Fail(ZulipApiException.Network("connection dropped")),
                EventStep.Hang(hang),
            ),
        )
        val store = storeWithQueue(queueId = "queue-old", lastEventId = 9)
        val errors = mutableListOf<String?>()
        val errorsJob = launch { store.error.collect { errors += it } }
        val poller = EventPoller(this, gateway, coordinator(gateway))

        poller.start(store)
        runCurrent()

        assertEquals(listOf<String?>(null), errors)
        assertEquals(ConnectionState.OFFLINE, store.connectionState.value)
        assertEquals(listOf("queue-old" to 9), gateway.eventCalls)

        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf<String?>(null), errors)
        assertEquals(listOf("queue-old" to 9, "queue-old" to 9), gateway.eventCalls)
        poller.stop()
        errorsJob.cancel()
        store.client.close()
    }

    @Test
    fun rejectedRegisterShowsOneErrorAndStops() = runTest {
        val gateway = ScriptedGateway(
            eventSteps = mutableListOf(
                EventStep.Fail(
                    ZulipApiException.ApiError(
                        statusCode = 400,
                        code = "BAD_REQUEST",
                        message = "Event 9 was not in this queue",
                    ),
                ),
            ),
            registerFailure = ZulipApiException.ApiError(
                statusCode = 401,
                code = "UNAUTHORIZED",
                message = "Not logged in",
            ),
        )
        val store = storeWithQueue(queueId = "queue-old", lastEventId = 9)
        val poller = EventPoller(this, gateway, coordinator(gateway))

        poller.start(store)
        runCurrent()

        assertEquals("Not logged in", store.error.value)
        assertFalse(poller.isRunning)
        assertEquals(1, gateway.registerCalls)

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("Not logged in", store.error.value)
        assertEquals(1, gateway.registerCalls)
        store.client.close()
    }

    private fun coordinator(gateway: ZulipGateway) = ZulipSyncCoordinator(
        gateway = gateway,
        cache = ZulipCacheRepository(NoOpCacheDao(), nowMillis = { 0L }),
    )

    private fun storeWithQueue(queueId: String, lastEventId: Int): ZulipStore {
        val account = StoredZulipAccount(
            id = 1L,
            realmUrl = "https://example.com",
            realmName = "Example",
            email = "user@example.com",
            userId = 1,
            zulipFeatureLevel = 0,
        )
        return ZulipStore(account, ZulipClient(account.realmUrl, account.email, "api-key")).also { store ->
            store.applyRegisterResponse(
                RegisterQueueResponse(result = "success", queueId = queueId, lastEventId = lastEventId),
            )
        }
    }
}

private sealed interface EventStep {
    data class Fail(val error: Throwable) : EventStep
    data class Hang(val deferred: CompletableDeferred<GetEventsResponse>) : EventStep
}

private class ScriptedGateway(
    private val eventSteps: MutableList<EventStep>,
    private val registerResponse: RegisterQueueResponse? = null,
    private val registerFailure: Throwable? = null,
) : ZulipGateway {
    val eventCalls = mutableListOf<Pair<String, Int>>()
    var registerCalls = 0

    override suspend fun register(client: ZulipClient, featureLevel: Int): RegisterQueueResponse {
        registerCalls += 1
        registerFailure?.let { throw it }
        return registerResponse ?: error("no register response configured")
    }

    override suspend fun events(
        client: ZulipClient,
        queueId: String,
        lastEventId: Int,
        timeoutMillis: Long,
        dontBlock: Boolean,
    ): GetEventsResponse {
        eventCalls += queueId to lastEventId
        return when (val step = eventSteps.removeAt(0)) {
            is EventStep.Fail -> throw step.error
            is EventStep.Hang -> step.deferred.await()
        }
    }

    override suspend fun messages(client: ZulipClient, narrow: Narrow): GetMessagesResponse =
        throw UnsupportedOperationException("not used")

    override suspend fun topics(client: ZulipClient, streamId: Int): StreamTopicsResponse =
        throw UnsupportedOperationException("not used")

    override suspend fun upload(client: ZulipClient, attachment: PendingUploadAttachment): UploadFileResponse =
        throw UnsupportedOperationException("not used")

    override suspend fun send(client: ZulipClient, narrow: Narrow, content: String): SendMessageResponse =
        throw UnsupportedOperationException("not used")
}

private class NoOpCacheDao : ZulipCacheDao {
    override suspend fun streams(accountId: Long): List<CachedStreamEntity> = emptyList()
    override suspend fun users(accountId: Long): List<CachedUserEntity> = emptyList()
    override suspend fun userById(accountId: Long, userId: Int): CachedUserEntity? = null
    override suspend fun topics(accountId: Long, streamId: Int): List<CachedTopicEntity> = emptyList()
    override suspend fun conversations(accountId: Long): List<CachedConversationEntity> = emptyList()
    override suspend fun messages(accountId: Long, narrowKey: String, limit: Int): List<CachedMessageEntity> = emptyList()
    override suspend fun messageNarrowKeys(accountId: Long): List<String> = emptyList()
    override suspend fun messageById(accountId: Long, messageId: Int): CachedMessageEntity? = null
    override suspend fun upsertStreams(rows: List<CachedStreamEntity>) = Unit
    override suspend fun upsertUsers(rows: List<CachedUserEntity>) = Unit
    override suspend fun upsertTopics(rows: List<CachedTopicEntity>) = Unit
    override suspend fun upsertConversations(rows: List<CachedConversationEntity>) = Unit
    override suspend fun upsertMessages(rows: List<CachedMessageEntity>) = Unit
    override suspend fun deleteStreams(accountId: Long) = Unit
    override suspend fun deleteUsers(accountId: Long) = Unit
    override suspend fun deleteTopics(accountId: Long, streamId: Int) = Unit
    override suspend fun deleteConversations(accountId: Long) = Unit
    override suspend fun deleteStream(accountId: Long, streamId: Int) = Unit
    override suspend fun deleteUser(accountId: Long, userId: Int) = Unit
    override suspend fun updateConversationUnreadCount(accountId: Long, narrowKey: String, unreadCount: Int) = Unit
    override suspend fun deleteMessages(accountId: Long, narrowKey: String) = Unit
    override suspend fun deleteMessagesOlderThan(accountId: Long, cutoff: Long) = Unit
    override suspend fun deleteMessagesExcept(accountId: Long, narrowKey: String, keepIds: List<Int>) = Unit
    override suspend fun deleteTopicsForAccount(accountId: Long) = Unit
    override suspend fun deleteMessagesForAccount(accountId: Long) = Unit
}
