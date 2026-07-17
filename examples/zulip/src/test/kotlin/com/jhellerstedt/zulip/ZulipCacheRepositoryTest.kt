package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.ZulipEvent
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.api.models.ZulipStream
import com.jhellerstedt.zulip.api.models.ZulipTopic
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.data.CachedConversationEntity
import com.jhellerstedt.zulip.data.CachedMessageEntity
import com.jhellerstedt.zulip.data.CachedStreamEntity
import com.jhellerstedt.zulip.data.CachedTopicEntity
import com.jhellerstedt.zulip.data.CachedUserEntity
import com.jhellerstedt.zulip.data.ZulipCacheDao
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.store.InboxConversation
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.cacheKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZulipCacheRepositoryTest {
    private val now = 1_000_000_000L

    @Test
    fun registerSnapshotReplacesAccountMetadata() = runBlocking {
        val dao = FakeCacheDao()
        val repository = ZulipCacheRepository(dao, nowMillis = { now })
        val accountId = 7L
        dao.streamRows += CachedStreamEntity(accountId, 1, "stale", "", false, 0)
        dao.userRows += CachedUserEntity(accountId, 1, "stale", "", false, 0)
        dao.conversationRows += CachedConversationEntity(accountId, "stream:1:", "stale", 0, 0, 0, 0)

        repository.replaceRegisterSnapshot(
            accountId,
            RegisterQueueResponse(
                result = "success",
                subscriptions = listOf(ZulipStream("mobile", 4, "Announcements", true)),
                realmUsers = listOf(ZulipUser(9, "Ada", "ada@example.com")),
            ),
            listOf(InboxConversation(Narrow.Topic(4, "release"), "Mobile > release", 2, "Preview")),
        )

        assertEquals(listOf("mobile"), dao.streamRows.map { it.name })
        assertEquals(listOf("Ada"), dao.userRows.map { it.fullName })
        assertEquals(listOf("Mobile > release"), dao.conversationRows.map { it.title })
        assertTrue(dao.accountIds.all { it == accountId })
    }

    @Test
    fun messagesRoundTripInAscendingOrderAndSkipMalformedPayloads() = runBlocking {
        val dao = FakeCacheDao()
        val repository = ZulipCacheRepository(dao, nowMillis = { now })
        val accountId = 7L
        val narrow = Narrow.Topic(4, "release")

        repository.replaceMessages(
            accountId,
            narrow,
            listOf(message(9), message(3)),
        )
        dao.messageRows += CachedMessageEntity(accountId, 10, narrow.cacheKey(), "{invalid", now)
        dao.conversationRows += CachedConversationEntity(
            accountId,
            narrow.cacheKey(),
            "Mobile > release",
            0,
            9,
            9,
            now,
        )

        assertEquals(listOf(3, 9), repository.messages(accountId, narrow).map { it.id })
        assertEquals(listOf(3, 9), repository.loadAccount(accountId).messagesByNarrow.getValue(narrow).map { it.id })
        assertTrue(dao.messageRows.any { it.payloadJson.contains("\"id\":3") })
        assertTrue(dao.accountIds.all { it == accountId })
    }

    @Test
    fun topicsAndMessageEventsAreCachedForTheSuppliedAccount() = runBlocking {
        val dao = FakeCacheDao()
        val repository = ZulipCacheRepository(dao, nowMillis = { now })
        val accountId = 7L

        repository.replaceTopics(accountId, 4, listOf(ZulipTopic("release", 9)))
        repository.applyEvent(
            accountId,
            ZulipEvent(type = "message", message = message(12)),
            InboxConversation(Narrow.Topic(4, "release"), "Mobile > release", 1),
        )

        assertEquals(listOf("release"), repository.topics(accountId, 4).map { it.name })
        assertEquals(listOf(12), repository.messages(accountId, Narrow.Topic(4, "release")).map { it.id })
        assertTrue(dao.accountIds.all { it == accountId })
    }

    @Test
    fun loadAccountPrunesExpiredRowsBeforeReturningMessages() = runBlocking {
        val dao = FakeCacheDao()
        val repository = ZulipCacheRepository(dao, nowMillis = { now })
        val accountId = 7L
        val narrow = Narrow.Channel(4)
        dao.messageRows += (1..30).map { id ->
            CachedMessageEntity(accountId, id, narrow.cacheKey(), Json.encodeToString(message(id)), now)
        }
        dao.messageRows += CachedMessageEntity(accountId, 99, "stream:5:", "{}", now - 604_800_001L)
        dao.conversationRows += CachedConversationEntity(
            accountId,
            narrow.cacheKey(),
            "Mobile",
            0,
            30,
            30,
            now,
        )

        val snapshot = repository.loadAccount(accountId)

        assertEquals((6..30).toSet(), dao.messageRows.filter { it.narrowKey == narrow.cacheKey() }.map { it.messageId }.toSet())
        assertTrue(dao.messageRows.none { it.narrowKey == "stream:5:" })
        assertEquals((6..30).toSet(), snapshot.messagesByNarrow.getValue(narrow).map { it.id }.toSet())
        assertEquals(now - 604_800_000L, dao.deletedBefore.single())
        assertTrue(dao.accountIds.all { it == accountId })
    }

    @Test
    fun pruneDeletesExpiredRowsAndKeepsTwentyFiveNewestMessagesPerNarrow() = runBlocking {
        val dao = FakeCacheDao()
        val repository = ZulipCacheRepository(dao, nowMillis = { now })
        val accountId = 7L
        val narrow = Narrow.Channel(4)
        dao.messageRows += (1..30).map { id ->
            CachedMessageEntity(accountId, id, narrow.cacheKey(), Json.encodeToString(message(id)), now)
        }
        dao.messageRows += CachedMessageEntity(accountId, 99, "stream:5:", "{}", now - 604_800_001L)

        repository.prune(accountId)

        assertEquals((6..30).toSet(), dao.messageRows.filter { it.narrowKey == narrow.cacheKey() }.map { it.messageId }.toSet())
        assertTrue(dao.messageRows.none { it.narrowKey == "stream:5:" })
        assertEquals(now - 604_800_000L, dao.deletedBefore.single())
        assertTrue(dao.accountIds.all { it == accountId })
    }

    private fun message(id: Int) = ZulipMessage(
        id = id,
        type = "stream",
        content = "message $id",
        streamId = 4,
        topic = "release",
        timestamp = id.toLong(),
    )
}

private class FakeCacheDao : ZulipCacheDao {
    val streamRows = mutableListOf<CachedStreamEntity>()
    val userRows = mutableListOf<CachedUserEntity>()
    val topicRows = mutableListOf<CachedTopicEntity>()
    val conversationRows = mutableListOf<CachedConversationEntity>()
    val messageRows = mutableListOf<CachedMessageEntity>()
    val accountIds = mutableListOf<Long>()
    val deletedBefore = mutableListOf<Long>()

    override suspend fun streams(accountId: Long): List<CachedStreamEntity> {
        accountIds += accountId
        return streamRows.filter { it.accountId == accountId }
    }

    override suspend fun users(accountId: Long): List<CachedUserEntity> {
        accountIds += accountId
        return userRows.filter { it.accountId == accountId }
    }

    override suspend fun topics(accountId: Long, streamId: Int): List<CachedTopicEntity> {
        accountIds += accountId
        return topicRows.filter { it.accountId == accountId && it.streamId == streamId }
    }

    override suspend fun conversations(accountId: Long): List<CachedConversationEntity> {
        accountIds += accountId
        return conversationRows.filter { it.accountId == accountId }
    }

    override suspend fun messages(accountId: Long, narrowKey: String, limit: Int): List<CachedMessageEntity> {
        accountIds += accountId
        return messageRows
            .filter { it.accountId == accountId && it.narrowKey == narrowKey }
            .sortedByDescending { it.messageId }
            .take(limit)
    }

    override suspend fun messageNarrowKeys(accountId: Long): List<String> {
        accountIds += accountId
        return messageRows.filter { it.accountId == accountId }.map { it.narrowKey }.distinct()
    }

    override suspend fun upsertStreams(rows: List<CachedStreamEntity>) {
        rows.forEach { row ->
            accountIds += row.accountId
            streamRows.removeAll { it.accountId == row.accountId && it.streamId == row.streamId }
            streamRows += row
        }
    }

    override suspend fun upsertUsers(rows: List<CachedUserEntity>) {
        rows.forEach { row ->
            accountIds += row.accountId
            userRows.removeAll { it.accountId == row.accountId && it.userId == row.userId }
            userRows += row
        }
    }

    override suspend fun upsertTopics(rows: List<CachedTopicEntity>) {
        rows.forEach { row ->
            accountIds += row.accountId
            topicRows.removeAll { it.accountId == row.accountId && it.streamId == row.streamId && it.topicName == row.topicName }
            topicRows += row
        }
    }

    override suspend fun upsertConversations(rows: List<CachedConversationEntity>) {
        rows.forEach { row ->
            accountIds += row.accountId
            conversationRows.removeAll { it.accountId == row.accountId && it.narrowKey == row.narrowKey }
            conversationRows += row
        }
    }

    override suspend fun upsertMessages(rows: List<CachedMessageEntity>) {
        rows.forEach { row ->
            accountIds += row.accountId
            messageRows.removeAll {
                it.accountId == row.accountId && it.narrowKey == row.narrowKey && it.messageId == row.messageId
            }
            messageRows += row
        }
    }

    override suspend fun deleteStreams(accountId: Long) {
        accountIds += accountId
        streamRows.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteUsers(accountId: Long) {
        accountIds += accountId
        userRows.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteTopics(accountId: Long, streamId: Int) {
        accountIds += accountId
        topicRows.removeAll { it.accountId == accountId && it.streamId == streamId }
    }

    override suspend fun deleteConversations(accountId: Long) {
        accountIds += accountId
        conversationRows.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteMessages(accountId: Long, narrowKey: String) {
        accountIds += accountId
        messageRows.removeAll { it.accountId == accountId && it.narrowKey == narrowKey }
    }

    override suspend fun deleteMessagesOlderThan(accountId: Long, cutoff: Long) {
        accountIds += accountId
        deletedBefore += cutoff
        messageRows.removeAll { it.accountId == accountId && it.cachedAt < cutoff }
    }

    override suspend fun deleteMessagesExcept(accountId: Long, narrowKey: String, keepIds: List<Int>) {
        accountIds += accountId
        messageRows.removeAll {
            it.accountId == accountId && it.narrowKey == narrowKey && it.messageId !in keepIds
        }
    }

    override suspend fun deleteTopicsForAccount(accountId: Long) {
        accountIds += accountId
        topicRows.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteMessagesForAccount(accountId: Long) {
        accountIds += accountId
        messageRows.removeAll { it.accountId == accountId }
    }
}
