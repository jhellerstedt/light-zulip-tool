package com.thelightphone.zulip.ui

import com.thelightphone.sdk.LightViewModel
import com.thelightphone.zulip.api.models.ZulipUser
import com.thelightphone.zulip.store.GlobalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChooseParticipantsUiState(
    val users: List<ZulipUser> = emptyList(),
    val selectedUserIds: List<Int> = emptyList(),
)

class ChooseParticipantsViewModel(
    globalStore: GlobalStore,
) : LightViewModel<Unit>() {
    private val initialUsers = globalStore.activeStore.value?.selectableUsers().orEmpty()

    private val _uiState = MutableStateFlow(
        ChooseParticipantsUiState(users = initialUsers),
    )
    val uiState: StateFlow<ChooseParticipantsUiState> = _uiState.asStateFlow()

    fun addParticipant(userId: Int) {
        _uiState.update { state ->
            if (userId in state.selectedUserIds) {
                state
            } else {
                state.copy(selectedUserIds = state.selectedUserIds + userId)
            }
        }
    }

    fun clearParticipants() {
        _uiState.update { it.copy(selectedUserIds = emptyList()) }
    }
}
