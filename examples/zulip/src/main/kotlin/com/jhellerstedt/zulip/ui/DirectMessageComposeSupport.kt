package com.jhellerstedt.zulip.ui

import com.jhellerstedt.zulip.api.models.ZulipUser

data class SelectedParticipantBadge(
    val userId: Int,
    val initials: String,
)

fun participantInitials(fullName: String): String {
    val parts = fullName
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    if (parts.size == 1) return parts.first().take(1).uppercase()
    return buildString {
        append(parts.first().first().uppercaseChar())
        append(parts.last().first().uppercaseChar())
    }
}

fun shortlistParticipantSuggestions(
    users: List<ZulipUser>,
    selectedUserIds: Set<Int>,
    recentUserIds: List<Int>,
    limit: Int,
): List<ZulipUser> {
    val available = users.filterNot { it.userId in selectedUserIds }
    val byId = available.associateBy { it.userId }
    val recent = recentUserIds
        .mapNotNull(byId::get)
        .distinctBy { it.userId }
        .take(limit)
    val recentIds = recent.mapTo(mutableSetOf()) { it.userId }
    val fallback = available
        .sortedBy { it.fullName.lowercase() }
        .filterNot { it.userId in recentIds }
        .take(limit - recent.size)
    return recent + fallback
}

fun filterParticipantSuggestions(
    users: List<ZulipUser>,
    query: String,
    selectedUserIds: Set<Int>,
    recentUserIds: List<Int> = emptyList(),
    shortlistLimit: Int = 6,
): List<ZulipUser> {
    val trimmed = query.trim().lowercase()
    if (trimmed.isBlank()) {
        return shortlistParticipantSuggestions(
            users = users,
            selectedUserIds = selectedUserIds,
            recentUserIds = recentUserIds,
            limit = shortlistLimit,
        )
    }

    return users
        .asSequence()
        .filterNot { it.userId in selectedUserIds }
        .filter { user ->
            user.fullName.lowercase().contains(trimmed) ||
                user.email.lowercase().contains(trimmed)
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

fun buildSelectedParticipantBadges(
    selectedUserIds: List<Int>,
    usersById: Map<Int, ZulipUser>,
): List<SelectedParticipantBadge> = selectedUserIds.map { userId ->
    val initials = usersById[userId]
        ?.fullName
        ?.let(::participantInitials)
        ?: "U$userId"
    SelectedParticipantBadge(
        userId = userId,
        initials = initials,
    )
}
