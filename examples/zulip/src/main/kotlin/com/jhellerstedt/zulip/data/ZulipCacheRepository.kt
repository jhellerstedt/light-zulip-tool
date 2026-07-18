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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        // Union with distinct cached_messages narrow keys so previously opened (now-read)
        // conversations keep their cached messages even after register replaces conversation
        // rows with only the currently-unread set.
        val narrowKeys = (conversations.map { it.narrowKey } + dao.messageNarrowKeys(accountId)).distinct()
        return CachedAccountSnapshot(
            streams = dao.streams(accountId).map { it.toZulipStream() },
            users = dao.users(accountId).map { it.toZulipUser() },
            conversations = conversations,
            messagesByNarrow = narrowKeys.mapNotNull { narrowKey ->
                narrowFromCacheKey(narrowKey)?.let { narrow ->
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
        when (event.type) {
            "message" -> if (event.message != null && conversation != null) {
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
            "update_message_flags" -> if (event.operation == "add" && event.flag == "read") {
                event.messages.orEmpty().forEach { messageId ->
                    markCachedMessageRead(accountId, messageId, cachedAt)
                }
            }
            "subscription" -> when (event.op) {
                "add" -> dao.upsertStreams(event.subscriptions.orEmpty().map { it.toCachedEntity(accountId, cachedAt) })
                "remove" -> event.subscriptions.orEmpty().forEach { stream ->
                    dao.deleteStream(accountId, stream.streamId)
                }
            }
            "realm_user" -> persistRealmUserEvent(accountId, event, cachedAt)
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
        prune(accountId)
    }

    /** Marks a cached message read in place and recomputes its conversation's unread count. */
    private suspend fun markCachedMessageRead(accountId: Long, messageId: Int, cachedAt: Long) {
        val row = dao.messageById(accountId, messageId) ?: return
        val message = runCatching { json.decodeFromString<ZulipMessage>(row.payloadJson) }.getOrNull() ?: return
        if (message.flags.contains("read")) return
        dao.upsertMessages(listOf(row.copy(payloadJson = json.encodeToString(message.copy(flags = message.flags + "read")))))
        val remainingUnread = dao.messages(accountId, row.narrowKey, MESSAGE_LIMIT).count { candidate ->
            runCatching { json.decodeFromString<ZulipMessage>(candidate.payloadJson) }
                .getOrNull()
                ?.flags
                ?.contains("read") == false
        }
        dao.updateConversationUnreadCount(accountId, row.narrowKey, remainingUnread)
    }

    /** Mirrors [com.jhellerstedt.zulip.store.ZulipStore]'s realm_user merge semantics for durability. */
    private suspend fun persistRealmUserEvent(accountId: Long, event: ZulipEvent, cachedAt: Long) {
        val person = event.person?.jsonObject ?: return
        val userId = person["user_id"]?.jsonPrimitive?.intOrNull ?: return
        when (event.op) {
            "add" -> dao.upsertUsers(listOf(mergeRealmUserEventPerson(accountId, userId, person, dao.userById(accountId, userId), cachedAt)))
            "update" -> {
                val existing = dao.userById(accountId, userId)
                val hasUpdatableField = person.containsKey("full_name") ||
                    person.containsKey("email") ||
                    person.containsKey("is_active")
                if (existing != null || hasUpdatableField) {
                    dao.upsertUsers(listOf(mergeRealmUserEventPerson(accountId, userId, person, existing, cachedAt)))
                }
            }
            "remove" -> dao.deleteUser(accountId, userId)
        }
    }

    private fun mergeRealmUserEventPerson(
        accountId: Long,
        userId: Int,
        person: Map<String, kotlinx.serialization.json.JsonElement>,
        existing: CachedUserEntity?,
        updatedAt: Long,
    ) = CachedUserEntity(
        accountId = accountId,
        userId = userId,
        fullName = person["full_name"]?.jsonPrimitive?.contentOrNull ?: existing?.fullName ?: "User $userId",
        email = person["email"]?.jsonPrimitive?.contentOrNull ?: existing?.email.orEmpty(),
        isActive = person["is_active"]?.jsonPrimitive?.booleanOrNull ?: existing?.isActive ?: true,
        updatedAt = updatedAt,
    )

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
