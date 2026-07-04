package com.thelightphone.zulip

import com.thelightphone.zulip.api.models.RegisterQueueResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentPrivateConversationsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun registerResponseParsesRecentPrivateConversations() {
        val response = json.decodeFromString<RegisterQueueResponse>(
            """
            {
              "result": "success",
              "recent_private_conversations": [
                {
                  "user_ids": [42, 73],
                  "max_message_id": 501
                }
              ]
            }
            """.trimIndent(),
        )

        val conversation = response.recentPrivateConversations.single()
        assertEquals(listOf(42, 73), conversation.userIds)
        assertEquals(501, conversation.maxMessageId)
    }
}
