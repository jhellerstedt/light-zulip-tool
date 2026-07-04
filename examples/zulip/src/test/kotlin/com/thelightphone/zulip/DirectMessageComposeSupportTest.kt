package com.thelightphone.zulip

import com.thelightphone.zulip.api.models.ZulipUser
import com.thelightphone.zulip.ui.buildParticipantSummary
import com.thelightphone.zulip.ui.filterParticipantSuggestions
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
    fun buildsCommaSeparatedParticipantSummary() {
        val summary = buildParticipantSummary(
            selectedUserIds = listOf(2, 1),
            usersById = users.associateBy { it.userId },
        )

        assertEquals("Bob Brown, Alice Adams", summary)
    }
}
