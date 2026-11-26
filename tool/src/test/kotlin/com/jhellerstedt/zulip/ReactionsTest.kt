package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.models.ZulipEvent
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.api.models.ZulipReaction
import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.ZulipStore
import com.jhellerstedt.zulip.util.ReactionEmoji
import com.jhellerstedt.zulip.util.reactionSummary
import com.jhellerstedt.zulip.util.replacingOwnReaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionsTest {
    @Test
    fun settingAReactionReplacesTheReadersPreviousOne() {
        val heart = ZulipReaction(emojiName = "heart", emojiCode = "2764", userId = 7)
        val message = ZulipMessage(
            id = 1,
            type = "stream",
            content = "hi",
            reactions = listOf(heart, ZulipReaction(emojiName = "fire", emojiCode = "1f525", userId = 3)),
        )

        val updated = message.replacingOwnReaction(7, ReactionEmoji.rows[1][1])

        assertEquals(listOf("1f525", "1f44d"), updated.reactions.map { it.emojiCode })
        assertEquals(
            "Ada reacted 🔥 · You reacted 👍",
            reactionSummary(updated, ownUserId = 7, names = mapOf(3 to "Ada")),
        )
    }

    @Test
    fun reactionEventAddsAndRemoves() {
        val store = store()
        try {
            store.applyMessages(
                Narrow.Topic(4, "general"),
                listOf(ZulipMessage(id = 9, type = "stream", content = "hi", streamId = 4, subject = "general")),
            )
            store.handleEvent(
                ZulipEvent(
                    type = "reaction",
                    op = "add",
                    userId = 3,
                    messageId = 9,
                    emojiName = "heart",
                    emojiCode = "2764",
                    reactionType = "unicode_emoji",
                ),
            )
            assertEquals("2764", store.getMessagesFor(Narrow.Topic(4, "general")).single().reactions.single().emojiCode)
            assertTrue(store.messagesRevision.value > 0)

            store.handleEvent(
                ZulipEvent(
                    type = "reaction",
                    op = "remove",
                    userId = 3,
                    messageId = 9,
                    emojiName = "heart",
                    emojiCode = "2764",
                    reactionType = "unicode_emoji",
                ),
            )
            assertEquals(emptyList(), store.getMessagesFor(Narrow.Topic(4, "general")).single().reactions)
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
            userId = 7,
            zulipFeatureLevel = 0,
        ),
        client = ZulipClient("https://example.com", "user@example.com", "api-key"),
    )
}
