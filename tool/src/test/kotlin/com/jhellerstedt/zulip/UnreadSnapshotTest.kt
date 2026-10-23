package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.ZulipEvent
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.api.models.ZulipStream
import com.jhellerstedt.zulip.data.CachedAccountSnapshot
import com.jhellerstedt.zulip.data.CachedConversationEntity
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.ZulipStore
import com.jhellerstedt.zulip.store.cacheKey
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnreadSnapshotTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun unreadStreamSnapshotPreservesTopicConversation() {
        val response = json.decodeFromString<RegisterQueueResponse>(
            """
            {
              "result": "success",
              "queue_id": "queue-1",
              "last_event_id": 10,
              "subscriptions": [
                {
                  "stream_id": 59,
                  "name": "general",
                  "description": ""
                }
              ],
              "messages": [],
              "realm_users": [],
              "unread_msgs": {
                "count": 1,
                "streams": [
                  {
                    "stream_id": 59,
                    "topic": "announcements",
                    "unread_message_ids": [101]
                  }
                ],
                "pms": [],
                "huddles": []
              }
            }
            """.trimIndent(),
        )

        val store = ZulipStore(
            account = StoredZulipAccount(
                id = 1L,
                realmUrl = "https://example.com",
                realmName = "Example",
                email = "user@example.com",
                userId = 1,
                zulipFeatureLevel = 0,
            ),
            client = ZulipClient("https://example.com", "user@example.com", "api-key"),
        )

        store.applyRegisterResponse(response)

        val conversation = store.inbox.value.single()
        assertEquals(Narrow.Topic(59, "announcements"), conversation.narrow)
        assertEquals(1, conversation.unreadCount)
        store.client.close()
    }

    @Test
    fun unreadStreamSnapshotDecodesUnreadMessageIds() {
        val response = json.decodeFromString<RegisterQueueResponse>(
            """
            {
              "result": "success",
              "unread_msgs": {
                "count": 1,
                "streams": [
                  {
                    "stream_id": 59,
                    "topic": "announcements",
                    "unread_message_ids": [101]
                  }
                ],
                "pms": [],
                "huddles": []
              }
            }
            """.trimIndent(),
        )

        assertTrue(response.unreadMsgs?.streams?.single()?.unreadIds?.contains(101) == true)
    }

    @Test
    fun readFlagEventDecrementsStoredUnreadCountWithoutRecountingCachedWindow() {
        val narrow = Narrow.Topic(59, "announcements")
        val store = ZulipStore(
            account = StoredZulipAccount(
                id = 1L,
                realmUrl = "https://example.com",
                realmName = "Example",
                email = "user@example.com",
                userId = 1,
                zulipFeatureLevel = 0,
            ),
            client = ZulipClient("https://example.com", "user@example.com", "api-key"),
        )
        store.applyCachedSnapshot(
            CachedAccountSnapshot(
                streams = listOf(ZulipStream("general", 59, "", false)),
                users = emptyList(),
                conversations = listOf(
                    CachedConversationEntity(
                        accountId = 1L,
                        narrowKey = narrow.cacheKey(),
                        title = "general > announcements",
                        unreadCount = 100,
                        lastMessageId = 101,
                        lastMessageTimestamp = 101L,
                        updatedAt = 0L,
                    ),
                ),
                messagesByNarrow = mapOf(
                    narrow to listOf(
                        ZulipMessage(
                            id = 101,
                            type = "stream",
                            content = "hello",
                            streamId = 59,
                            topic = "announcements",
                            timestamp = 101L,
                        ),
                    ),
                ),
            ),
        )

        store.handleEvent(
            ZulipEvent(type = "update_message_flags", operation = "add", flag = "read", messages = listOf(101)),
        )

        assertEquals(99, store.inbox.value.single().unreadCount)
        assertTrue(store.getMessagesFor(narrow).single().flags.contains("read"))
        store.client.close()
    }
}
