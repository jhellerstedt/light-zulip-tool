package com.thelightphone.zulip.ui

import com.thelightphone.zulip.api.models.ZulipMessage
import com.thelightphone.zulip.api.models.ZulipUser

fun participantSuggestionsPlaceholder(
    users: List<ZulipUser>,
    query: String,
    selectedUserIds: List<Int>,
): String? {
    if (users.isEmpty()) return "No realm users available"
    val suggestions = filterParticipantSuggestions(users, query, selectedUserIds.toSet())
    return if (suggestions.isEmpty()) "No matches" else null
}

fun messageAuthorName(
    message: ZulipMessage,
    usersById: Map<Int, String>,
): String {
    return message.sender?.fullName
        ?: message.senderFullName.takeIf { it.isNotBlank() }
        ?: message.senderId?.let(usersById::get)
        ?: "Unknown"
}

fun initialMessageScrollIndex(messages: List<ZulipMessage>): Int =
    if (messages.isEmpty()) -1 else messages.lastIndex
