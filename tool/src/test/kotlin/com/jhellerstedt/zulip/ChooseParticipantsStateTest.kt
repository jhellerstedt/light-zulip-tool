package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.ui.PickerUiState
import com.jhellerstedt.zulip.ui.activateInput
import com.jhellerstedt.zulip.ui.addParticipant
import com.jhellerstedt.zulip.ui.clearParticipants
import com.jhellerstedt.zulip.ui.hideKeyboard
import com.jhellerstedt.zulip.ui.removeParticipant
import com.jhellerstedt.zulip.ui.updateQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChooseParticipantsStateTest {
    @Test
    fun activateInputShowsKeyboard() {
        val updated = PickerUiState().activateInput()

        assertTrue(updated.isKeyboardVisible)
    }

    @Test
    fun hideKeyboardPreservesCurrentQuery() {
        val updated = PickerUiState(query = "ali", isKeyboardVisible = true).hideKeyboard()

        assertEquals("ali", updated.query)
        assertFalse(updated.isKeyboardVisible)
    }

    @Test
    fun updateQueryReplacesQueryAndPreservesOtherState() {
        val updated = PickerUiState(
            selectedUserIds = listOf(1),
            query = "ali",
            isKeyboardVisible = true,
            recentUserIds = listOf(3, 2),
        ).updateQuery("bob")

        assertEquals(listOf(1), updated.selectedUserIds)
        assertEquals("bob", updated.query)
        assertTrue(updated.isKeyboardVisible)
        assertEquals(listOf(3, 2), updated.recentUserIds)
    }

    @Test
    fun addParticipantClearsQueryAndHidesKeyboardAndPromotesRecentUser() {
        val updated = PickerUiState(
            selectedUserIds = listOf(1),
            query = "bob",
            isKeyboardVisible = true,
            recentUserIds = listOf(3),
        ).addParticipant(2)

        assertEquals(listOf(1, 2), updated.selectedUserIds)
        assertEquals("", updated.query)
        assertFalse(updated.isKeyboardVisible)
        assertEquals(listOf(2, 3), updated.recentUserIds)
    }

    @Test
    fun addParticipantIsIdempotentForDuplicateUser() {
        val initial = PickerUiState(
            selectedUserIds = listOf(1, 2),
            query = "bob",
            isKeyboardVisible = true,
            recentUserIds = listOf(3, 2),
        )

        val updated = initial.addParticipant(2)

        assertEquals(initial, updated)
    }

    @Test
    fun removeParticipantDropsOnlyTheTappedUser() {
        val updated = PickerUiState(selectedUserIds = listOf(1, 2, 3)).removeParticipant(2)

        assertEquals(listOf(1, 3), updated.selectedUserIds)
    }

    @Test
    fun clearParticipantsClearsSelectionAndQueryButPreservesKeyboardAndRecents() {
        val updated = PickerUiState(
            selectedUserIds = listOf(1, 2),
            query = "carol",
            isKeyboardVisible = true,
            recentUserIds = listOf(3, 2),
        ).clearParticipants()

        assertEquals(emptyList(), updated.selectedUserIds)
        assertEquals("", updated.query)
        assertTrue(updated.isKeyboardVisible)
        assertEquals(listOf(3, 2), updated.recentUserIds)
    }
}
