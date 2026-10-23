package com.jhellerstedt.zulip.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "cached_streams",
    primaryKeys = ["account_id", "stream_id"],
    foreignKeys = [
        ForeignKey(
            entity = ZulipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CachedStreamEntity(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "stream_id") val streamId: Int,
    val name: String,
    val description: String,
    @ColumnInfo(name = "invite_only") val inviteOnly: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "cached_users",
    primaryKeys = ["account_id", "user_id"],
    foreignKeys = [
        ForeignKey(
            entity = ZulipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CachedUserEntity(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "user_id") val userId: Int,
    @ColumnInfo(name = "full_name") val fullName: String,
    val email: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "cached_topics",
    primaryKeys = ["account_id", "stream_id", "topic_name"],
    foreignKeys = [
        ForeignKey(
            entity = ZulipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CachedTopicEntity(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "stream_id") val streamId: Int,
    @ColumnInfo(name = "topic_name") val topicName: String,
    @ColumnInfo(name = "max_message_id") val maxMessageId: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "cached_conversations",
    primaryKeys = ["account_id", "narrow_key"],
    foreignKeys = [
        ForeignKey(
            entity = ZulipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CachedConversationEntity(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "narrow_key") val narrowKey: String,
    val title: String,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "last_message_id") val lastMessageId: Int,
    @ColumnInfo(name = "last_message_timestamp") val lastMessageTimestamp: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "cached_messages",
    primaryKeys = ["account_id", "narrow_key", "message_id"],
    foreignKeys = [
        ForeignKey(
            entity = ZulipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "narrow_key", "cached_at"]),
    ],
)
data class CachedMessageEntity(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "message_id") val messageId: Int,
    @ColumnInfo(name = "narrow_key") val narrowKey: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
)
