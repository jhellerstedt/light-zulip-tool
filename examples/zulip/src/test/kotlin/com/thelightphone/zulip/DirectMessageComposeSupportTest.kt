package com.thelightphone.zulip

import com.thelightphone.zulip.api.models.ZulipUser
import com.thelightphone.zulip.ui.buildParticipantSummary
import com.thelightphone.zulip.ui.buildSelectedParticipantBadges
import com.thelightphone.zulip.ui.filterParticipantSuggestions
import com.thelightphone.zulip.ui.participantInitials
import com.thelightphone.zulip.ui.shortlistParticipantSuggestions
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectMessageComposeSupportTest {
    private val users = listOf(
        ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
        ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
        ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
    )

    @Test
    fun filtersSuggestionsByNameAndEmailExcludingSelectedUsers() {
        val byName = filterParticipantSuggestions(users, query = "ali", selectedUserIds = setOf())
        val byEmail = filterParticipantSuggestions(users, query = "bob@", selectedUserIds = setOf())
        val excludingSelected = filterParticipantSuggestions(users, query = "car", selectedUserIds = setOf(1))

        assertEquals(listOf(1), byName.map { it.userId })
        assertEquals(listOf(2), byEmail.map { it.userId })
        assertEquals(listOf(3), excludingSelected.map { it.userId })
    }

    @Test
    fun buildsInitialsForSelectedParticipants() {
        assertEquals("AA", participantInitials("Alice Adams"))
        assertEquals("BC", participantInitials("Bob Charles"))
        assertEquals("Q", participantInitials("Q"))
    }

    @Test
    fun emptyQueryUsesShortRelevantListInsteadOfFullDirectory() {
        val shortlist = filterParticipantSuggestions(
            users = users,
            query = "",
            selectedUserIds = setOf(1),
            recentUserIds = listOf(3, 2, 99),
            shortlistLimit = 2,
        )

        assertEquals(listOf(3, 2), shortlist.map { it.userId })
    }

    @Test
    fun shortlistFallsBackToAlphabeticalUsersWhenNoRecentMatchesRemain() {
        val shortlist = shortlistParticipantSuggestions(
            users = users,
            selectedUserIds = setOf(2),
            recentUserIds = listOf(2),
            limit = 2,
        )

        assertEquals(listOf(1, 3), shortlist.map { it.userId })
    }

    @Test
    fun shortlistBackfillsAfterSurvivingRecentUsers() {
        val shortlist = shortlistParticipantSuggestions(
            users = users,
            selectedUserIds = setOf(2),
            recentUserIds = listOf(2, 3),
            limit = 2,
        )

        assertEquals(listOf(3, 1), shortlist.map { it.userId })
    }

    @Test
    fun buildsCommaSeparatedParticipantSummary() {
        val summary = buildParticipantSummary(
            selectedUserIds = listOf(2, 1),
            usersById = users.associateBy { it.userId },
        )

        assertEquals("Bob Brown, Alice Adams", summary)
    }

    @Test
    fun buildsBadgeDisplayForSelectedParticipants() {
        val badges = buildSelectedParticipantBadges(
            selectedUserIds = listOf(2, 1),
            usersById = users.associateBy { it.userId },
        )

        assertEquals(listOf("BB", "AA"), badges.map { it.initials })
    }
}
