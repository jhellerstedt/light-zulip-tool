package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.ZulipGateway
import com.jhellerstedt.zulip.api.models.GetEventsResponse
import com.jhellerstedt.zulip.api.models.GetMessagesResponse
import com.jhellerstedt.zulip.api.models.StreamTopicsResponse
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.SendMessageResponse
import com.jhellerstedt.zulip.api.models.UploadFileResponse
import com.jhellerstedt.zulip.data.CachedConversationEntity
import com.jhellerstedt.zulip.data.CachedMessageEntity
import com.jhellerstedt.zulip.data.CachedStreamEntity
import com.jhellerstedt.zulip.data.CachedTopicEntity
import com.jhellerstedt.zulip.data.CachedUserEntity
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.data.ZulipAccountStore
import com.jhellerstedt.zulip.data.ZulipCacheDao
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.PendingUploadAttachment

/**
 * Shared fakes for [com.jhellerstedt.zulip.ui.StreamListViewModel] and
 * [com.jhellerstedt.zulip.ui.DirectMessageListViewModel] smoke tests: a minimal
 * account store, a cache DAO that only needs pre-seeded rows, and a gateway whose
 * network calls always fail immediately so tests can assert on cache-only hydration.
 */
internal fun fixtureListAccount(id: Long, email: String) = StoredZulipAccount(
    id = id,
    realmUrl = "https://example.com",
    realmName = "Example",
    email = email,
    userId = 1,
    zulipFeatureLevel = 0,
)

internal class InMemoryListAccountStore(accounts: List<StoredZulipAccount>) : ZulipAccountStore {
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
    ): StoredZulipAccount = error("not used in list smoke tests")

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

/** A gateway whose calls always fail immediately, so register never overwrites cache-only state. */
internal class ImmediatelyFailingListGateway : ZulipGateway {
    override suspend fun register(client: ZulipClient, featureLevel: Int): RegisterQueueResponse =
        throw RuntimeException("offline")

    override suspend fun events(
        client: ZulipClient,
        queueId: String,
        lastEventId: Int,
        timeoutMillis: Long,
        dontBlock: Boolean,
    ): GetEventsResponse = throw RuntimeException("offline")

    override suspend fun messages(client: ZulipClient, narrow: Narrow): GetMessagesResponse =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun topics(client: ZulipClient, streamId: Int): StreamTopicsResponse =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun upload(client: ZulipClient, attachment: PendingUploadAttachment): UploadFileResponse =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun send(client: ZulipClient, narrow: Narrow, content: String): SendMessageResponse =
        throw UnsupportedOperationException("not used in this test")
}

/** A [ZulipCacheDao] fake that only needs pre-seeded rows; all writes are no-ops for these read-only tests. */
internal class SeedableListCacheDao : ZulipCacheDao {
    val streamRows = mutableListOf<CachedStreamEntity>()
    val userRows = mutableListOf<CachedUserEntity>()
    val topicRows = mutableListOf<CachedTopicEntity>()
    val conversationRows = mutableListOf<CachedConversationEntity>()
    val messageRows = mutableListOf<CachedMessageEntity>()

    override suspend fun streams(accountId: Long): List<CachedStreamEntity> =
        streamRows.filter { it.accountId == accountId }

    override suspend fun users(accountId: Long): List<CachedUserEntity> =
        userRows.filter { it.accountId == accountId }

    override suspend fun userById(accountId: Long, userId: Int): CachedUserEntity? =
        userRows.firstOrNull { it.accountId == accountId && it.userId == userId }

    override suspend fun topics(accountId: Long, streamId: Int): List<CachedTopicEntity> =
        topicRows.filter { it.accountId == accountId && it.streamId == streamId }

    override suspend fun conversations(accountId: Long): List<CachedConversationEntity> =
        conversationRows.filter { it.accountId == accountId }

    override suspend fun messages(accountId: Long, narrowKey: String, limit: Int): List<CachedMessageEntity> =
        messageRows
            .filter { it.accountId == accountId && it.narrowKey == narrowKey }
            .sortedByDescending { it.messageId }
            .take(limit)

    override suspend fun messageNarrowKeys(accountId: Long): List<String> =
        messageRows.filter { it.accountId == accountId }.map { it.narrowKey }.distinct()

    override suspend fun messageById(accountId: Long, messageId: Int): CachedMessageEntity? =
        messageRows.firstOrNull { it.accountId == accountId && it.messageId == messageId }

    override suspend fun upsertStreams(rows: List<CachedStreamEntity>) {
        streamRows += rows
    }

    override suspend fun upsertUsers(rows: List<CachedUserEntity>) {
        userRows += rows
    }

    override suspend fun upsertTopics(rows: List<CachedTopicEntity>) {
        topicRows += rows
    }

    override suspend fun upsertConversations(rows: List<CachedConversationEntity>) {
        conversationRows += rows
    }

    override suspend fun upsertMessages(rows: List<CachedMessageEntity>) {
        messageRows += rows
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
        if (index >= 0) {
            conversationRows[index] = conversationRows[index].copy(unreadCount = unreadCount)
        }
    }

    override suspend fun deleteMessages(accountId: Long, narrowKey: String) {
        messageRows.removeAll { it.accountId == accountId && it.narrowKey == narrowKey }
    }

    override suspend fun deleteMessagesOlderThan(accountId: Long, cutoff: Long) {
        messageRows.removeAll { it.accountId == accountId && it.cachedAt < cutoff }
    }

    override suspend fun deleteMessagesExcept(accountId: Long, narrowKey: String, keepIds: List<Int>) {
        messageRows.removeAll {
            it.accountId == accountId && it.narrowKey == narrowKey && it.messageId !in keepIds
        }
    }

    override suspend fun deleteTopicsForAccount(accountId: Long) {
        topicRows.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteMessagesForAccount(accountId: Long) {
        messageRows.removeAll { it.accountId == accountId }
    }
}
