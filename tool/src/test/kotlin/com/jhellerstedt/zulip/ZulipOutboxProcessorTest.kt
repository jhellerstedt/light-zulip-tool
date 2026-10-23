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
import com.jhellerstedt.zulip.data.OutboxFileStore
import com.jhellerstedt.zulip.data.OutboxMessageState
import com.jhellerstedt.zulip.data.PendingAttachmentEntity
import com.jhellerstedt.zulip.data.PendingMessageEntity
import com.jhellerstedt.zulip.data.PendingOutboxItem
import com.jhellerstedt.zulip.data.ZulipOutboxDao
import com.jhellerstedt.zulip.data.ZulipOutboxRepository
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.OutboxRunResult
import com.jhellerstedt.zulip.store.PendingUploadAttachment
import com.jhellerstedt.zulip.store.ZulipOutboxProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZulipOutboxProcessorTest {
    @Test
    fun uploadsInOrderCheckpointsEachUrlAndSendsFinalMarkdown() = runTest {
        val dao = ProcessorOutboxDao()
        val first = attachment("first", 0)
        val second = attachment("second", 1)
        dao.add(message("one"), first, second)
        val gateway = RecordingGateway()
        val client = ZulipClient("https://example.com", "user@example.com", "key")

        val result = processor(dao, gateway, client).process(accountId)

        assertEquals(OutboxRunResult.Success, result)
        assertEquals(listOf("upload:first.jpg", "upload:second.jpg", "send"), gateway.calls)
        assertEquals(
            "caption-one\n\n[first.jpg](/uploads/first.jpg)\n[second.jpg](/uploads/second.jpg)",
            gateway.sentContent,
        )
        assertTrue(dao.messages.isEmpty())
        client.close()
    }

    @Test
    fun retryReusesCheckpointedUploadAndRequeuesCurrentMessage() = runTest {
        val dao = ProcessorOutboxDao()
        dao.add(message("one"), attachment("first", 0), attachment("second", 1))
        val gateway = RecordingGateway().apply {
            uploadFailures["second.jpg"] = ZulipApiException.Network("timeout", SocketTimeoutException())
        }
        val client = ZulipClient("https://example.com", "user@example.com", "key")
        val processor = processor(dao, gateway, client)

        assertEquals(OutboxRunResult.Retry, processor.process(accountId))
        gateway.uploadFailures.clear()
        assertEquals(OutboxRunResult.Success, processor.process(accountId))

        assertEquals(listOf("upload:first.jpg", "upload:second.jpg", "upload:second.jpg", "send"), gateway.calls)
        assertTrue(dao.messages.isEmpty())
        client.close()
    }

    @Test
    fun permanentFailureMarksRowFailedAndContinuesToLaterRows() = runTest {
        val dao = ProcessorOutboxDao()
        dao.add(message("bad", createdAt = 1))
        dao.add(message("good", createdAt = 2))
        val gateway = RecordingGateway().apply {
            sendFailures["caption-bad"] = ZulipApiException.ApiError(422, null, "bad recipient")
        }
        val client = ZulipClient("https://example.com", "user@example.com", "key")

        assertEquals(OutboxRunResult.Success, processor(dao, gateway, client).process(accountId))

        assertEquals(OutboxMessageState.FAILED, dao.messages.single().state)
        assertEquals("bad recipient", dao.messages.single().failureReason)
        assertEquals(listOf("send", "send"), gateway.calls)
        client.close()
    }

    @Test
    fun sendTimeoutRequeuesWithoutClaimingDelivery() = runTest {
        val dao = ProcessorOutboxDao()
        dao.add(message("one"))
        val gateway = RecordingGateway().apply {
            sendFailures["caption-one"] = ZulipApiException.Network("send timeout", SocketTimeoutException())
        }
        val client = ZulipClient("https://example.com", "user@example.com", "key")

        assertEquals(OutboxRunResult.Retry, processor(dao, gateway, client).process(accountId))

        assertEquals(OutboxMessageState.QUEUED, dao.messages.single().state)
        assertFalse(dao.messages.isEmpty())
        client.close()
    }

    private fun processor(
        dao: ProcessorOutboxDao,
        gateway: RecordingGateway,
        client: ZulipClient,
    ) = ZulipOutboxProcessor(
        repository = ZulipOutboxRepository(dao, NoOpFileStore),
        gateway = gateway,
        client = client,
    )

    private fun message(id: String, createdAt: Long = 1) = PendingMessageEntity(
        localId = id,
        accountId = accountId,
        narrowKey = "stream:1:topic",
        content = "caption-$id",
        state = OutboxMessageState.QUEUED,
        createdAt = createdAt,
        attemptAt = null,
        failureReason = null,
    )

    private fun attachment(id: String, sourceIndex: Int) = PendingAttachmentEntity(
        localId = id,
        accountId = accountId,
        messageLocalId = "one",
        sourceIndex = sourceIndex,
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        filePath = Files.createTempFile(id, ".jpg").toFile().apply { writeText(id) }.path,
        uploadedUrl = null,
    )

    private companion object {
        const val accountId = 1L
    }
}

private object NoOpFileStore : OutboxFileStore {
    override fun copyIn(accountId: Long, messageId: String, attachmentId: String, source: File): File = source
    override fun delete(file: File) = Unit
    override fun deleteAccount(accountId: Long) = Unit
}

private class ProcessorOutboxDao : ZulipOutboxDao {
    val messages = mutableListOf<PendingMessageEntity>()
    private val pendingAttachments = mutableListOf<PendingAttachmentEntity>()

    fun add(message: PendingMessageEntity, vararg attachments: PendingAttachmentEntity) {
        messages += message
        pendingAttachments += attachments.map { it.copy(messageLocalId = message.localId) }
    }

    override suspend fun insertMessage(message: PendingMessageEntity) {
        messages += message
    }

    override suspend fun insertAttachments(attachments: List<PendingAttachmentEntity>) {
        pendingAttachments += attachments
    }

    override fun pendingCount(accountId: Long): Flow<Int> = emptyFlow()
    override fun firstFailedMessage(accountId: Long): Flow<PendingMessageEntity?> = emptyFlow()

    override suspend fun oldestQueuedMessage(accountId: Long): PendingMessageEntity? =
        messages.filter { it.accountId == accountId && it.state == OutboxMessageState.QUEUED }
            .minByOrNull(PendingMessageEntity::createdAt)

    override suspend fun attachments(accountId: Long, messageLocalId: String): List<PendingAttachmentEntity> =
        pendingAttachments.filter { it.accountId == accountId && it.messageLocalId == messageLocalId }
            .sortedBy(PendingAttachmentEntity::sourceIndex)

    override suspend fun message(messageLocalId: String): PendingMessageEntity? =
        messages.firstOrNull { it.localId == messageLocalId }

    override suspend fun attachment(attachmentLocalId: String): PendingAttachmentEntity? =
        pendingAttachments.firstOrNull { it.localId == attachmentLocalId }

    override suspend fun markSending(accountId: Long, messageLocalId: String, attemptAt: Long) {
        update(messageLocalId) { it.copy(state = OutboxMessageState.SENDING, attemptAt = attemptAt) }
    }

    override suspend fun markQueued(accountId: Long, messageLocalId: String) {
        update(messageLocalId) { it.copy(state = OutboxMessageState.QUEUED, failureReason = null) }
    }

    override suspend fun markFailed(accountId: Long, messageLocalId: String, failureReason: String) {
        update(messageLocalId) { it.copy(state = OutboxMessageState.FAILED, failureReason = failureReason) }
    }

    override suspend fun checkpointUpload(accountId: Long, attachmentLocalId: String, uploadedUrl: String) {
        val index = pendingAttachments.indexOfFirst { it.localId == attachmentLocalId }
        pendingAttachments[index] = pendingAttachments[index].copy(uploadedUrl = uploadedUrl)
    }

    override suspend fun deleteMessage(accountId: Long, messageLocalId: String) {
        messages.removeAll { it.localId == messageLocalId }
        pendingAttachments.removeAll { it.messageLocalId == messageLocalId }
    }

    override suspend fun resetSending(accountId: Long) {
        messages.replaceAll {
            if (it.accountId == accountId && it.state == OutboxMessageState.SENDING) it.copy(state = OutboxMessageState.QUEUED) else it
        }
    }

    override suspend fun deleteMessagesForAccount(accountId: Long) = Unit

    private fun update(messageLocalId: String, transform: (PendingMessageEntity) -> PendingMessageEntity) {
        val index = messages.indexOfFirst { it.localId == messageLocalId }
        messages[index] = transform(messages[index])
    }
}

private class RecordingGateway : ZulipGateway {
    val calls = mutableListOf<String>()
    val uploadFailures = mutableMapOf<String, Throwable>()
    val sendFailures = mutableMapOf<String, Throwable>()
    var sentContent = ""

    override suspend fun upload(client: ZulipClient, attachment: PendingUploadAttachment): UploadFileResponse {
        calls += "upload:${attachment.displayName}"
        uploadFailures.remove(attachment.displayName)?.let { throw it }
        return UploadFileResponse("success", url = "/uploads/${attachment.displayName}")
    }

    override suspend fun send(client: ZulipClient, narrow: Narrow, content: String): SendMessageResponse {
        calls += "send"
        sentContent = content
        sendFailures.remove(content)?.let { throw it }
        return SendMessageResponse("success", id = 1)
    }

    override suspend fun register(client: ZulipClient, featureLevel: Int) = RegisterQueueResponse("success")
    override suspend fun events(client: ZulipClient, queueId: String, lastEventId: Int, timeoutMillis: Long, dontBlock: Boolean) =
        GetEventsResponse("success")
    override suspend fun messages(client: ZulipClient, narrow: Narrow) = GetMessagesResponse("success")
    override suspend fun topics(client: ZulipClient, streamId: Int) = StreamTopicsResponse("success")
}
