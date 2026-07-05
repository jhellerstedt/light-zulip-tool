package com.jhellerstedt.zulip

import androidx.compose.ui.text.TextRange
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.ui.ActiveMentionQuery
import com.jhellerstedt.zulip.ui.ComposeMentionToken
import com.jhellerstedt.zulip.ui.applyTextEditToMentionTokens
import com.jhellerstedt.zulip.ui.filterMentionSuggestions
import com.jhellerstedt.zulip.ui.findActiveMentionQuery
import com.jhellerstedt.zulip.ui.formatMessageWithMentions
import com.jhellerstedt.zulip.ui.insertMention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeMentionSupportTest {
    private val users = listOf(
        ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
        ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
        ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
    )

    @Test
    fun findsActiveMentionAtCursor() {
        val text = "Hello @ali"

        assertEquals(
            ActiveMentionQuery(start = 6, endExclusive = text.length, query = "ali"),
            findActiveMentionQuery(text = text, selection = TextRange(text.length)),
        )
    }

    @Test
    fun ignoresAtSymbolsInsideWords() {
        val text = "email me at hi@example.com"

        assertNull(findActiveMentionQuery(text = text, selection = TextRange(text.length)))
    }

    @Test
    fun blankMentionQueryShowsAlphabeticalSuggestions() {
        val suggestions = filterMentionSuggestions(users = users, query = "", limit = 2)

        assertEquals(listOf(1, 2), suggestions.map { it.userId })
    }

    @Test
    fun filtersMentionSuggestionsByNameAndEmail() {
        val byName = filterMentionSuggestions(users = users, query = "ali")
        val byEmail = filterMentionSuggestions(users = users, query = "bob@")

        assertEquals(listOf(1), byName.map { it.userId })
        assertEquals(listOf(2), byEmail.map { it.userId })
    }

    @Test
    fun insertMentionReplacesQueryAndAppendsTrailingSpace() {
        val result = insertMention(
            text = "Hello @ali",
            activeQuery = ActiveMentionQuery(start = 6, endExclusive = 10, query = "ali"),
            accountId = 1L,
            user = users.first(),
            tokens = emptyList(),
        )

        assertEquals("Hello @Alice Adams ", result.updatedText)
        assertEquals(TextRange("Hello @Alice Adams ".length), result.selection)
        assertEquals(
            listOf(
                ComposeMentionToken(
                    accountId = 1L,
                    userId = 1,
                    displayText = "@Alice Adams",
                    start = 6,
                    endExclusive = 18,
                ),
            ),
            result.tokens,
        )
    }

    @Test
    fun typingBeforeMentionShiftsTokenRange() {
        val shifted = applyTextEditToMentionTokens(
            tokens = listOf(
                ComposeMentionToken(
                    accountId = 1L,
                    userId = 1,
                    displayText = "@Alice Adams",
                    start = 6,
                    endExclusive = 18,
                ),
            ),
            start = 0,
            endExclusive = 0,
            insertedText = ">> ",
        )

        assertEquals(
            listOf(
                ComposeMentionToken(
                    accountId = 1L,
                    userId = 1,
                    displayText = "@Alice Adams",
                    start = 9,
                    endExclusive = 21,
                ),
            ),
            shifted,
        )
    }

    @Test
    fun editingInsideMentionDropsTheToken() {
        val updated = applyTextEditToMentionTokens(
            tokens = listOf(
                ComposeMentionToken(
                    accountId = 1L,
                    userId = 1,
                    displayText = "@Alice Adams",
                    start = 6,
                    endExclusive = 18,
                ),
            ),
            start = 10,
            endExclusive = 12,
            insertedText = "x",
        )

        assertEquals(emptyList(), updated)
    }

    @Test
    fun formatsMultipleConfirmedMentionsUsingIds() {
        val text = "Hi @Alice Adams and @Bob Brown"

        val formatted = formatMessageWithMentions(
            text = text,
            currentAccountId = 1L,
            tokens = listOf(
                ComposeMentionToken(
                    accountId = 1L,
                    userId = 1,
                    displayText = "@Alice Adams",
                    start = 3,
                    endExclusive = 15,
                ),
                ComposeMentionToken(
                    accountId = 1L,
                    userId = 2,
                    displayText = "@Bob Brown",
                    start = 20,
                    endExclusive = 30,
                ),
            ),
        )

        assertEquals("Hi @**Alice Adams|1** and @**Bob Brown|2**", formatted)
    }

    @Test
    fun invalidTokenLeavesVisiblePlainTextUntouched() {
        val text = "Hi @Alicf Adams"

        val formatted = formatMessageWithMentions(
            text = text,
            currentAccountId = 1L,
            tokens = listOf(
                ComposeMentionToken(
                    accountId = 1L,
                    userId = 1,
                    displayText = "@Alice Adams",
                    start = 3,
                    endExclusive = 15,
                ),
            ),
        )

        assertEquals(text, formatted)
    }

    @Test
    fun tokenFromDifferentAccountFallsBackToPlainText() {
        val text = "Hi @Alice Adams"

        val formatted = formatMessageWithMentions(
            text = text,
            currentAccountId = 2L,
            tokens = listOf(
                ComposeMentionToken(
                    accountId = 1L,
                    userId = 99,
                    displayText = "@Alice Adams",
                    start = 3,
                    endExclusive = 15,
                ),
            ),
        )

        assertEquals(text, formatted)
    }
}
