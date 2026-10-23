package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.ZulipEvent
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.store.ZulipStore
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamOrderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun streamsAreOrderedByNewestMessageThenName() {
        val store = store()
        try {
            store.applyRegisterResponse(
                json.decodeFromString<RegisterQueueResponse>(
                    """
                    {
                      "result": "success",
                      "subscriptions": [
                        {"name": "quiet", "stream_id": 1},
                        {"name": "old", "stream_id": 2},
                        {"name": "new", "stream_id": 3}
                      ],
                      "messages": [
                        {
                          "id": 10,
                          "type": "stream",
                          "content": "earlier",
                          "stream_id": 2,
                          "topic": "chat"
                        }
                      ],
                      "unread_msgs": {
                        "streams": [
                          {
                            "stream_id": 3,
                            "topic": "chat",
                            "unread_message_ids": [40]
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(listOf("new", "old", "quiet"), store.streamsList.value.map { it.name })

            store.handleEvent(
                ZulipEvent(
                    type = "message",
                    id = 51,
                    message = ZulipMessage(
                        id = 50,
                        type = "stream",
                        content = "latest",
                        streamId = 1,
                        topic = "chat",
                    ),
                ),
            )

            assertEquals(listOf("quiet", "new", "old"), store.streamsList.value.map { it.name })
        } finally {
            store.client.close()
        }
    }

    private fun store(): ZulipStore = ZulipStore(
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
}
