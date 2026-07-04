package com.thelightphone.zulip.ui

data class PickerUiState(
    val selectedUserIds: List<Int> = emptyList(),
    val query: String = "",
    val isKeyboardVisible: Boolean = false,
    val recentUserIds: List<Int> = emptyList(),
)

fun PickerUiState.activateInput(): PickerUiState =
    copy(isKeyboardVisible = true)

fun PickerUiState.hideKeyboard(): PickerUiState =
    copy(isKeyboardVisible = false)

fun PickerUiState.updateQuery(value: String): PickerUiState =
    copy(query = value)

fun PickerUiState.addParticipant(userId: Int): PickerUiState {
    if (userId in selectedUserIds) return this
    return copy(
        selectedUserIds = selectedUserIds + userId,
        query = "",
        isKeyboardVisible = false,
        recentUserIds = listOf(userId) + recentUserIds.filterNot { it == userId },
    )
}

fun PickerUiState.removeParticipant(userId: Int): PickerUiState =
    copy(selectedUserIds = selectedUserIds.filterNot { it == userId })

fun PickerUiState.clearParticipants(): PickerUiState =
    copy(selectedUserIds = emptyList(), query = "")
