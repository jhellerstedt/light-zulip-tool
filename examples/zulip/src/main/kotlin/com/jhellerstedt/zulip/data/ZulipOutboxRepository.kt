package com.jhellerstedt.zulip.data

import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.PendingUploadAttachment
import com.jhellerstedt.zulip.store.cacheKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.util.UUID

interface OutboxFileStore {
    fun copyIn(accountId: Long, messageId: String, attachmentId: String, source: File): File
    fun delete(file: File)
    fun deleteAccount(accountId: Long)
}

class FileOutboxFileStore(
    private val filesDir: File,
) : OutboxFileStore {
    override fun copyIn(accountId: Long, messageId: String, attachmentId: String, source: File): File {
        val target = File(filesDir, "zulip-outbox/$accountId/$messageId/$attachmentId")
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        return target
    }

    override fun delete(file: File) {
        file.delete()
    }

    override fun deleteAccount(accountId: Long) {
        File(filesDir, "zulip-outbox/$accountId").deleteRecursively()
    }
}

data class PendingOutboxItem(
    val message: PendingMessageEntity,
    val attachments: List<PendingAttachmentEntity>,
)

data class OutboxSummary(
    val pendingCount: Int,
    val firstFailure: PendingMessageEntity?,
)

class ZulipOutboxRepository(
    private val dao: ZulipOutboxDao,
    private val files: OutboxFileStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(
        accountId: Long,
        narrow: Narrow,
        content: String,
        attachments: List<PendingUploadAttachment>,
    ): Result<String> {
        val createdFiles = mutableListOf<File>()
        return runCatching {
            val messageId = newId()
            val pendingAttachments = attachments.mapIndexed { index, attachment ->
                val attachmentId = newId()
                val copiedFile = files.copyIn(accountId, messageId, attachmentId, attachment.file)
                createdFiles += copiedFile
                PendingAttachmentEntity(
                    localId = attachmentId,
                    accountId = accountId,
                    messageLocalId = messageId,
                    sourceIndex = index,
                    displayName = attachment.displayName,
                    mimeType = attachment.mimeType,
                    filePath = copiedFile.absolutePath,
                    uploadedUrl = null,
                )
            }
            dao.insertMessageWithAttachments(
                message = PendingMessageEntity(
                    localId = messageId,
                    accountId = accountId,
                    narrowKey = narrow.cacheKey(),
                    content = content,
                    state = OutboxMessageState.QUEUED,
                    createdAt = nowMillis(),
                    attemptAt = null,
                    failureReason = null,
                ),
                attachments = pendingAttachments,
            )
            messageId
        }.onFailure {
            createdFiles.forEach(files::delete)
        }
    }

    suspend fun claimNext(accountId: Long): PendingOutboxItem? = dao.claimNext(accountId, nowMillis())

    suspend fun checkpointUpload(attachmentId: String, url: String) {
        dao.attachment(attachmentId)?.let { attachment ->
            dao.checkpointUpload(attachment.accountId, attachmentId, url)
        }
    }

    suspend fun requeue(messageId: String) {
        dao.message(messageId)?.let { message ->
            dao.markQueued(message.accountId, messageId)
        }
    }

    suspend fun fail(messageId: String, reason: String) {
        dao.message(messageId)?.let { message ->
            dao.markFailed(message.accountId, messageId, reason)
        }
    }

    suspend fun complete(item: PendingOutboxItem) {
        dao.deleteMessage(item.message.accountId, item.message.localId)
        item.attachments.forEach { attachment -> files.delete(File(attachment.filePath)) }
    }

    suspend fun retry(messageId: String) {
        dao.message(messageId)
            ?.takeIf { it.state == OutboxMessageState.FAILED }
            ?.let { message -> dao.markQueued(message.accountId, messageId) }
    }

    suspend fun discard(messageId: String) {
        val message = dao.message(messageId) ?: return
        val attachments = dao.attachments(message.accountId, messageId)
        dao.deleteMessage(message.accountId, messageId)
        attachments.forEach { attachment -> files.delete(File(attachment.filePath)) }
    }

    suspend fun resetInterrupted(accountId: Long) {
        dao.resetSending(accountId)
    }

    suspend fun deleteAccount(accountId: Long) {
        dao.deleteMessagesForAccount(accountId)
        files.deleteAccount(accountId)
    }

    fun observeSummary(accountId: Long): Flow<OutboxSummary> =
        combine(dao.pendingCount(accountId), dao.firstFailedMessage(accountId), ::OutboxSummary)
}
