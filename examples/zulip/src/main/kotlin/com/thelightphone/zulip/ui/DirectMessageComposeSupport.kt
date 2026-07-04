package com.thelightphone.zulip.ui

import com.thelightphone.zulip.api.models.ZulipUser

fun filterParticipantSuggestions(
    users: List<ZulipUser>,
    query: String,
    selectedUserIds: Set<Int>,
): List<ZulipUser> {
    val trimmed = query.trim().lowercase()
    return users
        .asSequence()
        .filterNot { it.userId in selectedUserIds }
        .filter { user ->
            if (trimmed.isBlank()) {
                true
            } else {
                user.fullName.lowercase().contains(trimmed) ||
                    user.email.lowercase().contains(trimmed)
            }
        }
        .sortedBy { it.fullName.lowercase() }
        .toList()
}

fun buildParticipantSummary(
    selectedUserIds: List<Int>,
    usersById: Map<Int, ZulipUser>,
): String = selectedUserIds.joinToString(", ") { userId ->
    usersById[userId]?.fullName ?: "User $userId"
}
