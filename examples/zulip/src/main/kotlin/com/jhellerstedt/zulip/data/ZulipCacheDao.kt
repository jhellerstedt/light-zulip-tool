package com.jhellerstedt.zulip.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ZulipCacheDao {
    @Query("SELECT * FROM cached_streams WHERE account_id = :accountId ORDER BY name COLLATE NOCASE")
    suspend fun streams(accountId: Long): List<CachedStreamEntity>

    @Query("SELECT * FROM cached_users WHERE account_id = :accountId ORDER BY full_name COLLATE NOCASE")
    suspend fun users(accountId: Long): List<CachedUserEntity>

    @Query("SELECT * FROM cached_topics WHERE account_id = :accountId AND stream_id = :streamId ORDER BY max_message_id DESC")
    suspend fun topics(accountId: Long, streamId: Int): List<CachedTopicEntity>

    @Query("SELECT * FROM cached_conversations WHERE account_id = :accountId")
    suspend fun conversations(accountId: Long): List<CachedConversationEntity>

    @Query(
        "SELECT * FROM cached_messages WHERE account_id = :accountId AND narrow_key = :narrowKey " +
            "ORDER BY message_id DESC LIMIT :limit",
    )
    suspend fun messages(accountId: Long, narrowKey: String, limit: Int): List<CachedMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStreams(rows: List<CachedStreamEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsers(rows: List<CachedUserEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTopics(rows: List<CachedTopicEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(rows: List<CachedConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(rows: List<CachedMessageEntity>)

    @Query("DELETE FROM cached_messages WHERE account_id = :accountId AND cached_at < :cutoff")
    suspend fun deleteMessagesOlderThan(accountId: Long, cutoff: Long)

    @Query("DELETE FROM cached_messages WHERE account_id = :accountId AND narrow_key = :narrowKey AND message_id NOT IN (:keepIds)")
    suspend fun deleteMessagesExcept(accountId: Long, narrowKey: String, keepIds: List<Int>)
}
