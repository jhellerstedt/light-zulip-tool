package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.ZulipStore
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
}
