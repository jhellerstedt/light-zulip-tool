package com.jhellerstedt.zulip.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ZulipOutboxDao {
    @Transaction
    suspend fun insertMessageWithAttachments(
        message: PendingMessageEntity,
        attachments: List<PendingAttachmentEntity>,
    ) {
        insertMessage(message)
        insertAttachments(attachments)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: PendingMessageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachments(attachments: List<PendingAttachmentEntity>)

    @Query(
        "SELECT COUNT(*) FROM pending_messages WHERE account_id = :accountId " +
            "AND state IN ('QUEUED', 'SENDING')",
    )
    fun pendingCount(accountId: Long): Flow<Int>

    @Query(
        "SELECT * FROM pending_messages WHERE account_id = :accountId AND state = 'FAILED' " +
            "ORDER BY created_at ASC LIMIT 1",
    )
    fun firstFailedMessage(accountId: Long): Flow<PendingMessageEntity?>

    @Query(
        "SELECT * FROM pending_messages WHERE account_id = :accountId AND state = 'QUEUED' " +
            "ORDER BY created_at ASC LIMIT 1",
    )
    suspend fun oldestQueuedMessage(accountId: Long): PendingMessageEntity?

    @Query(
        "SELECT * FROM pending_attachments WHERE account_id = :accountId AND message_local_id = :messageLocalId " +
            "ORDER BY source_index ASC",
    )
    suspend fun attachments(accountId: Long, messageLocalId: String): List<PendingAttachmentEntity>

    @Query(
        "UPDATE pending_messages SET state = 'SENDING', attempt_at = :attemptAt " +
            "WHERE account_id = :accountId AND local_id = :messageLocalId",
    )
    suspend fun markSending(accountId: Long, messageLocalId: String, attemptAt: Long)

    @Query(
        "UPDATE pending_messages SET state = 'QUEUED', failure_reason = NULL " +
            "WHERE account_id = :accountId AND local_id = :messageLocalId",
    )
    suspend fun markQueued(accountId: Long, messageLocalId: String)

    @Query(
        "UPDATE pending_messages SET state = 'FAILED', failure_reason = :failureReason " +
            "WHERE account_id = :accountId AND local_id = :messageLocalId",
    )
    suspend fun markFailed(accountId: Long, messageLocalId: String, failureReason: String)

    @Query(
        "UPDATE pending_attachments SET uploaded_url = :uploadedUrl " +
            "WHERE local_id = :attachmentLocalId AND account_id = :accountId",
    )
    suspend fun checkpointUpload(accountId: Long, attachmentLocalId: String, uploadedUrl: String)

    @Query("DELETE FROM pending_messages WHERE account_id = :accountId AND local_id = :messageLocalId")
    suspend fun deleteMessage(accountId: Long, messageLocalId: String)
}
