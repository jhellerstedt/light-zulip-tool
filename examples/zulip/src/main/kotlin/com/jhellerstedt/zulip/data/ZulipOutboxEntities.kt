package com.jhellerstedt.zulip.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.TypeConverter

enum class OutboxMessageState {
    QUEUED,
    SENDING,
    FAILED,
}

class ZulipOutboxTypeConverters {
    @TypeConverter
    fun fromState(state: OutboxMessageState): String = state.name

    @TypeConverter
    fun toState(value: String): OutboxMessageState = OutboxMessageState.valueOf(value)
}

@Entity(
    tableName = "pending_messages",
    primaryKeys = ["local_id"],
    foreignKeys = [
        ForeignKey(
            entity = ZulipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "state", "created_at"]),
    ],
)
data class PendingMessageEntity(
    @ColumnInfo(name = "local_id") val localId: String,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "narrow_key") val narrowKey: String,
    val content: String,
    val state: OutboxMessageState,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempt_at") val attemptAt: Long?,
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
)

@Entity(
    tableName = "pending_attachments",
    primaryKeys = ["local_id"],
    foreignKeys = [
        ForeignKey(
            entity = ZulipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PendingMessageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["message_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["message_local_id"]),
        Index(value = ["message_local_id", "source_index"], unique = true),
    ],
)
data class PendingAttachmentEntity(
    @ColumnInfo(name = "local_id") val localId: String,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "message_local_id") val messageLocalId: String,
    @ColumnInfo(name = "source_index") val sourceIndex: Int,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "uploaded_url") val uploadedUrl: String?,
)
