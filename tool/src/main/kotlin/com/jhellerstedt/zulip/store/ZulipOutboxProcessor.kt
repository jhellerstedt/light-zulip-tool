package com.jhellerstedt.zulip.store

import com.jhellerstedt.zulip.api.ZulipApiException
import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.ZulipGateway
import com.jhellerstedt.zulip.data.PendingOutboxItem
import com.jhellerstedt.zulip.data.ZulipOutboxRepository
import com.jhellerstedt.zulip.ui.UploadedAttachmentLink
import com.jhellerstedt.zulip.ui.buildComposeMessageBody
import kotlinx.coroutines.CancellationException
import java.io.File

enum class OutboxFailureKind {
    TRANSIENT,
    PERMANENT,
}

sealed interface OutboxRunResult {
    data object Success : OutboxRunResult
    data object Retry : OutboxRunResult
}

fun classifyOutboxFailure(error: Throwable): OutboxFailureKind = when (error) {
    is ZulipApiException.Network -> OutboxFailureKind.TRANSIENT
    is ZulipApiException.ApiError ->
        if (error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500) {
            OutboxFailureKind.TRANSIENT
        } else {
            OutboxFailureKind.PERMANENT
        }
    else -> OutboxFailureKind.PERMANENT
}

class ZulipOutboxProcessor(
    private val repository: ZulipOutboxRepository,
    private val gateway: ZulipGateway,
    private val client: ZulipClient? = null,
    private val clientForAccount: ((Long) -> ZulipClient)? = null,
) {
    suspend fun process(accountId: Long): OutboxRunResult {
        val accountClient = client ?: requireNotNull(clientForAccount) {
            "An outbox processor requires a client or client provider"
        }.invoke(accountId)
        return try {
            repository.resetInterrupted(accountId)
            while (true) {
                val item = repository.claimNext(accountId) ?: return OutboxRunResult.Success
                try {
                    processItem(item, accountClient)
                    repository.complete(item)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    when (classifyOutboxFailure(error)) {
                        OutboxFailureKind.TRANSIENT -> {
                            repository.requeue(item.message.localId)
                            return OutboxRunResult.Retry
                        }
                        OutboxFailureKind.PERMANENT -> {
                            repository.fail(item.message.localId, error.message ?: "Permanent outbox failure")
                        }
                    }
                }
            }
            error("Unreachable")
        } finally {
            if (client == null) accountClient.close()
        }
    }

    private suspend fun processItem(item: PendingOutboxItem, client: ZulipClient) {
        val narrow = narrowFromCacheKey(item.message.narrowKey)
            ?: throw IllegalArgumentException("Invalid outbox narrow: ${item.message.narrowKey}")
        val links = item.attachments.map { attachment ->
            val url = attachment.uploadedUrl ?: gateway.upload(
                client,
                PendingUploadAttachment(
                    displayName = attachment.displayName,
                    mimeType = attachment.mimeType,
                    file = File(attachment.filePath),
                ),
            ).let { response ->
                (response.url.ifBlank { response.uri }).also {
                    require(it.isNotBlank()) { "Upload response did not include a URL" }
                    repository.checkpointUpload(attachment.localId, it)
                }
            }
            UploadedAttachmentLink(attachment.displayName, url)
        }
        gateway.send(client, narrow, buildComposeMessageBody(item.message.content, links))
    }
}
