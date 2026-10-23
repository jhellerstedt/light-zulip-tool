package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.models.GetMessagesResponse
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.ui.initialMessageScrollIndex
import com.jhellerstedt.zulip.ui.messageAuthorName
import com.jhellerstedt.zulip.ui.participantSuggestionsPlaceholder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessagePresentationSupportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun blankParticipantQueryWithAvailableUsersShowsSuggestionsInsteadOfPrompt() {
        val placeholder = participantSuggestionsPlaceholder(
            users = listOf(
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
            ),
            query = "",
            selectedUserIds = listOf(1),
        )

        assertNull(placeholder)
    }

    @Test
    fun messageAuthorNameUsesSenderFullNameFromApiPayload() {
        val response = json.decodeFromString<GetMessagesResponse>(
            """
            {
              "result": "success",
              "messages": [
                {
                  "id": 101,
                  "type": "private",
                  "content": "hello",
                  "display_recipient": [],
                  "sender_id": 42,
                  "sender_full_name": "Alice Adams",
                  "timestamp": 1710000000,
                  "flags": []
                }
              ]
            }
            """.trimIndent(),
        )

        val author = messageAuthorName(
            response.messages.single(),
            usersById = emptyMap(),
        )

        assertEquals("Alice Adams", author)
    }

    @Test
    fun initialMessageScrollIndexTargetsLatestMessage() {
        val index = initialMessageScrollIndex(
            listOf(
                ZulipMessage(id = 1, type = "private", content = "a"),
                ZulipMessage(id = 2, type = "private", content = "b"),
                ZulipMessage(id = 3, type = "private", content = "c"),
            ),
        )

        assertEquals(2, index)
    }
}
