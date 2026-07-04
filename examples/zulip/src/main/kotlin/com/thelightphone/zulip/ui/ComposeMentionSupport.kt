package com.thelightphone.zulip.ui

import androidx.compose.ui.text.TextRange
import com.thelightphone.zulip.api.models.ZulipUser

data class ComposeMentionToken(
    val accountId: Long,
    val userId: Int,
    val displayText: String,
    val start: Int,
    val endExclusive: Int,
)

data class ActiveMentionQuery(
    val start: Int,
    val endExclusive: Int,
    val query: String,
)

data class MentionInsertionResult(
    val updatedText: String,
    val selection: TextRange,
    val tokens: List<ComposeMentionToken>,
)

fun findActiveMentionQuery(text: CharSequence, selection: TextRange): ActiveMentionQuery? {
    if (!selection.collapsed) return null

    val cursor = selection.start
    if (cursor < 0 || cursor > text.length) return null

    for (index in (cursor - 1) downTo 0) {
        val character = text[index]
        if (character.isWhitespace()) break
        if (character == '@' && hasValidMentionBoundary(text, index)) {
            return ActiveMentionQuery(
                start = index,
                endExclusive = cursor,
                query = text.subSequence(index + 1, cursor).toString(),
            )
        }
    }

    return null
}

fun filterMentionSuggestions(
    users: List<ZulipUser>,
    query: String,
    limit: Int = 5,
): List<ZulipUser> {
    val normalizedQuery = query.trim().lowercase()
    val sortedUsers = users.sortedBy { it.fullName.lowercase() }

    if (normalizedQuery.isBlank()) {
        return sortedUsers.take(limit)
    }

    return sortedUsers
        .filter { user ->
            user.fullName.lowercase().contains(normalizedQuery) ||
                user.email.lowercase().contains(normalizedQuery)
        }
        .take(limit)
}

fun applyTextEditToMentionTokens(
    tokens: List<ComposeMentionToken>,
    start: Int,
    endExclusive: Int,
    insertedText: String,
): List<ComposeMentionToken> {
    val delta = insertedText.length - (endExclusive - start)

    return tokens
        .mapNotNull { token ->
            when {
                token.endExclusive <= start -> token
                token.start >= endExclusive -> token.copy(
                    start = token.start + delta,
                    endExclusive = token.endExclusive + delta,
                )
                else -> null
            }
        }
        .sortedBy { it.start }
}

fun insertMention(
    text: CharSequence,
    activeQuery: ActiveMentionQuery,
    accountId: Long,
    user: ZulipUser,
    tokens: List<ComposeMentionToken>,
): MentionInsertionResult {
    val displayText = "@${user.fullName}"
    val replacement = "$displayText "
    val updatedText = buildString {
        append(text.subSequence(0, activeQuery.start))
        append(replacement)
        append(text.subSequence(activeQuery.endExclusive, text.length))
    }
    val updatedTokens = applyTextEditToMentionTokens(
        tokens = tokens,
        start = activeQuery.start,
        endExclusive = activeQuery.endExclusive,
        insertedText = replacement,
    ) + ComposeMentionToken(
        accountId = accountId,
        userId = user.userId,
        displayText = displayText,
        start = activeQuery.start,
        endExclusive = activeQuery.start + displayText.length,
    )

    return MentionInsertionResult(
        updatedText = updatedText,
        selection = TextRange(activeQuery.start + replacement.length),
        tokens = updatedTokens.sortedBy { it.start },
    )
}

fun formatMessageWithMentions(
    text: String,
    tokens: List<ComposeMentionToken>,
    currentAccountId: Long?,
): String {
    var formatted = text

    for (token in tokens.sortedByDescending { it.start }) {
        if (currentAccountId == null || token.accountId != currentAccountId) {
            continue
        }
        if (token.start < 0 || token.endExclusive > formatted.length) {
            continue
        }
        if (formatted.substring(token.start, token.endExclusive) != token.displayText) {
            continue
        }

        val replacement = "@**${token.displayText.removePrefix("@")}|${token.userId}**"
        formatted = formatted.replaceRange(token.start, token.endExclusive, replacement)
    }

    return formatted
}

private fun hasValidMentionBoundary(text: CharSequence, atIndex: Int): Boolean {
    if (atIndex == 0) return true

    val previous = text[atIndex - 1]
    return previous.isWhitespace() || previous in setOf('(', '[', '{', '"', '\'')
}
