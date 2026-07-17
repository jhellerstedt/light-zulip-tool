package com.jhellerstedt.zulip.store

import com.jhellerstedt.zulip.api.ZulipGateway
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.ZulipEvent
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.api.models.ZulipTopic
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import kotlinx.coroutines.CancellationException

/**
 * Hydrates [ZulipStore] instances from the persistent cache and reconciles them
 * with the Zulip network APIs, keeping Room and in-memory state in sync.
 */
class ZulipSyncCoordinator(
    private val gateway: ZulipGateway,
    private val cache: ZulipCacheRepository,
) {
    suspend fun hydrate(store: ZulipStore): HydrationResult {
        val snapshot = cache.loadAccount(store.account.id)
        store.applyCachedSnapshot(snapshot)
        val hasCachedState = snapshot.streams.isNotEmpty() ||
            snapshot.users.isNotEmpty() ||
            snapshot.conversations.isNotEmpty() ||
            snapshot.messagesByNarrow.values.any { it.isNotEmpty() }
        return HydrationResult(hasCachedState)
    }

    suspend fun register(store: ZulipStore): Result<Unit> {
        store.setConnectionState(ConnectionState.REFRESHING)
        return try {
            val response = registerWithCompatibilityRetry(store)
            cache.replaceRegisterSnapshot(store.account.id, response, response.toInboxConversations())
            store.applyRegisterResponse(response)
            store.setConnectionState(ConnectionState.ONLINE)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            store.setConnectionState(ConnectionState.OFFLINE)
            Result.failure(error)
        }
    }

    suspend fun refreshMessages(store: ZulipStore, narrow: Narrow): Result<List<ZulipMessage>> = runCatching {
        val response = gateway.messages(store.client, narrow)
        val sorted = response.messages.sortedBy { it.id }
        cache.replaceMessages(store.account.id, narrow, sorted)
        store.applyMessages(narrow, sorted)
        sorted
    }

    suspend fun cachedTopics(store: ZulipStore, streamId: Int): List<ZulipTopic> =
        cache.topics(store.account.id, streamId)

    suspend fun refreshTopics(store: ZulipStore, streamId: Int): Result<List<ZulipTopic>> = runCatching {
        val response = gateway.topics(store.client, streamId)
        val topics = response.topics.sortedByDescending { it.maxId }
        cache.replaceTopics(store.account.id, streamId, topics)
        topics
    }

    suspend fun applyEvent(store: ZulipStore, event: ZulipEvent) {
        store.handleEvent(event)
        val narrow = store.narrowForEvent(event)
        val conversation = narrow?.let { target ->
            store.inbox.value.firstOrNull { it.narrow.cacheKey() == target.cacheKey() }
        }
        cache.applyEvent(store.account.id, event, conversation)
    }

    private suspend fun registerWithCompatibilityRetry(store: ZulipStore): RegisterQueueResponse {
        return try {
            gateway.register(store.client, store.account.zulipFeatureLevel)
        } catch (error: Exception) {
            if (shouldDowngradeIdleQueueTimeout(error)) {
                store.updateAccountMetadata(store.account.copy(zulipFeatureLevel = 0))
                gateway.register(store.client, store.account.zulipFeatureLevel)
            } else {
                throw error
            }
        }
    }

    private fun RegisterQueueResponse.toInboxConversations(): List<InboxConversation> {
        val streamNames = subscriptions.associate { it.streamId to it.name }
        val userNames = realmUsers.associate { it.userId to it.fullName }
        val conversations = mutableListOf<InboxConversation>()
        unreadMsgs?.streams?.forEach { unreadStream ->
            if (unreadStream.unreadIds.isEmpty()) return@forEach
            val narrow = if (unreadStream.topic.isBlank()) {
                Narrow.Channel(unreadStream.streamId)
            } else {
                Narrow.Topic(unreadStream.streamId, unreadStream.topic)
            }
            conversations += InboxConversation(
                narrow = narrow,
                title = narrow.displayTitle(streamNames, userNames),
                unreadCount = unreadStream.unreadIds.size,
            )
        }
        unreadMsgs?.pms?.forEach { pm ->
            if (pm.unreadIds.isEmpty()) return@forEach
            val narrow = Narrow.Dm(listOf(pm.senderId))
            conversations += InboxConversation(
                narrow = narrow,
                title = narrow.displayTitle(streamNames, userNames),
                unreadCount = pm.unreadIds.size,
            )
        }
        return conversations
    }
}
