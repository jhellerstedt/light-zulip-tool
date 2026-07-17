package com.jhellerstedt.zulip.data

import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.ZulipEvent
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.api.models.ZulipStream
import com.jhellerstedt.zulip.api.models.ZulipTopic
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.store.InboxConversation
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.cacheKey
import com.jhellerstedt.zulip.store.narrowFromCacheKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days

data class CachedAccountSnapshot(
    val streams: List<ZulipStream>,
    val users: List<ZulipUser>,
    val conversations: List<CachedConversationEntity>,
    val messagesByNarrow: Map<Narrow, List<ZulipMessage>>,
)

class ZulipCacheRepository(
    private val dao: ZulipCacheDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun loadAccount(accountId: Long): CachedAccountSnapshot {
        prune(accountId)
        val conversations = dao.conversations(accountId)
        return CachedAccountSnapshot(
            streams = dao.streams(accountId).map { it.toZulipStream() },
            users = dao.users(accountId).map { it.toZulipUser() },
            conversations = conversations,
            messagesByNarrow = conversations.mapNotNull { conversation ->
                narrowFromCacheKey(conversation.narrowKey)?.let { narrow ->
                    narrow to messages(accountId, narrow)
                }
            }.toMap(),
        )
    }

    suspend fun messages(accountId: Long, narrow: Narrow): List<ZulipMessage> =
        dao.messages(accountId, narrow.cacheKey(), MESSAGE_LIMIT)
            .mapNotNull { row ->
                runCatching { json.decodeFromString<ZulipMessage>(row.payloadJson) }.getOrNull()
            }
            .sortedBy(ZulipMessage::id)

    suspend fun topics(accountId: Long, streamId: Int): List<ZulipTopic> =
        dao.topics(accountId, streamId).map { it.toZulipTopic() }

    suspend fun replaceRegisterSnapshot(
        accountId: Long,
        response: RegisterQueueResponse,
        conversations: List<InboxConversation>,
    ) {
        val updatedAt = nowMillis()
        dao.replaceRegisterSnapshot(
            accountId = accountId,
            streams = response.subscriptions.map { it.toCachedEntity(accountId, updatedAt) },
            users = response.realmUsers.map { it.toCachedEntity(accountId, updatedAt) },
            conversations = conversations.map { conversation ->
                val latestMessage = response.messages
                    .filter { it.belongsTo(conversation.narrow) }
                    .maxByOrNull(ZulipMessage::id)
                CachedConversationEntity(
                    accountId = accountId,
                    narrowKey = conversation.narrow.cacheKey(),
                    title = conversation.title,
                    unreadCount = conversation.unreadCount,
                    lastMessageId = latestMessage?.id ?: 0,
                    lastMessageTimestamp = latestMessage?.timestamp ?: 0,
                    updatedAt = updatedAt,
                )
            },
        )
    }

    suspend fun replaceMessages(accountId: Long, narrow: Narrow, messages: List<ZulipMessage>) {
        val cachedAt = nowMillis()
        dao.replaceMessages(
            accountId = accountId,
            narrowKey = narrow.cacheKey(),
            messages = messages.map { message ->
                CachedMessageEntity(
                    accountId = accountId,
                    messageId = message.id,
                    narrowKey = narrow.cacheKey(),
                    payloadJson = json.encodeToString(message),
                    cachedAt = cachedAt,
                )
            },
        )
        prune(accountId)
    }

    suspend fun replaceTopics(accountId: Long, streamId: Int, topics: List<ZulipTopic>) {
        val updatedAt = nowMillis()
        dao.replaceTopics(
            accountId = accountId,
            streamId = streamId,
            topics = topics.map { it.toCachedEntity(accountId, streamId, updatedAt) },
        )
    }

    suspend fun applyEvent(accountId: Long, event: ZulipEvent, conversation: InboxConversation?) {
        val cachedAt = nowMillis()
        if (event.type == "message" && event.message != null && conversation != null) {
            dao.upsertMessages(
                listOf(
                    CachedMessageEntity(
                        accountId = accountId,
                        messageId = event.message.id,
                        narrowKey = conversation.narrow.cacheKey(),
                        payloadJson = json.encodeToString(event.message),
                        cachedAt = cachedAt,
                    ),
                ),
            )
        }
        if (conversation != null) {
            val message = event.message
            dao.upsertConversations(
                listOf(
                    CachedConversationEntity(
                        accountId = accountId,
                        narrowKey = conversation.narrow.cacheKey(),
                        title = conversation.title,
                        unreadCount = conversation.unreadCount,
                        lastMessageId = message?.id ?: 0,
                        lastMessageTimestamp = message?.timestamp ?: 0,
                        updatedAt = cachedAt,
                    ),
                ),
            )
        }
        if (event.type == "subscription" && event.op == "add") {
            dao.upsertStreams(event.subscriptions.orEmpty().map { it.toCachedEntity(accountId, cachedAt) })
        }
        prune(accountId)
    }

    suspend fun prune(accountId: Long) {
        dao.deleteMessagesOlderThan(accountId, nowMillis() - 7.days.inWholeMilliseconds)
        dao.messageNarrowKeys(accountId).forEach { narrowKey ->
            val newestIds = dao.messages(accountId, narrowKey, MESSAGE_LIMIT).map(CachedMessageEntity::messageId)
            dao.deleteMessagesExcept(accountId, narrowKey, newestIds)
        }
    }

    suspend fun deleteAccount(accountId: Long) {
        dao.deleteAccount(accountId)
    }

    private fun ZulipMessage.belongsTo(narrow: Narrow): Boolean = when (narrow) {
        is Narrow.Channel -> type == "stream" && streamId == narrow.streamId
        is Narrow.Topic -> type == "stream" && streamId == narrow.streamId &&
            (subject.ifBlank { topic } == narrow.topic)
        is Narrow.Dm -> false
    }

    private fun CachedStreamEntity.toZulipStream() = ZulipStream(name, streamId, description, inviteOnly)

    private fun ZulipUser.toCachedEntity(accountId: Long, updatedAt: Long) = CachedUserEntity(
        accountId = accountId,
        userId = userId,
        fullName = fullName,
        email = email,
        isActive = isActive,
        updatedAt = updatedAt,
    )

    private fun CachedUserEntity.toZulipUser() = ZulipUser(userId, fullName, email, isActive)

    private fun ZulipStream.toCachedEntity(accountId: Long, updatedAt: Long) = CachedStreamEntity(
        accountId = accountId,
        streamId = streamId,
        name = name,
        description = description,
        inviteOnly = inviteOnly,
        updatedAt = updatedAt,
    )

    private fun CachedTopicEntity.toZulipTopic() = ZulipTopic(topicName, maxMessageId)

    private fun ZulipTopic.toCachedEntity(accountId: Long, streamId: Int, updatedAt: Long) = CachedTopicEntity(
        accountId = accountId,
        streamId = streamId,
        topicName = name,
        maxMessageId = maxId,
        updatedAt = updatedAt,
    )

    private companion object {
        const val MESSAGE_LIMIT = 25
    }
}
