package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.jhellerstedt.zulip.store.DirectMessageConversation
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.ZulipStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChooseParticipantsUiState(
    val users: List<ZulipUser> = emptyList(),
    val selectedUserIds: List<Int> = emptyList(),
    val query: String = "",
    val isKeyboardVisible: Boolean = false,
    val recentUserIds: List<Int> = emptyList(),
)

private fun ChooseParticipantsUiState.toPickerState() = PickerUiState(
    selectedUserIds = selectedUserIds,
    query = query,
    isKeyboardVisible = isKeyboardVisible,
    recentUserIds = recentUserIds,
)

private fun ChooseParticipantsUiState.fromPickerState(picker: PickerUiState) = copy(
    selectedUserIds = picker.selectedUserIds,
    query = picker.query,
    isKeyboardVisible = picker.isKeyboardVisible,
    recentUserIds = picker.recentUserIds,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun selectableUsersFlow(activeStore: Flow<ZulipStore?>): Flow<List<ZulipUser>> =
    activeStore.flatMapLatest { store -> store?.selectableUsersFlow ?: flowOf(emptyList()) }

internal fun recentUserIdsFromConversations(
    conversations: List<DirectMessageConversation>,
): List<Int> = conversations
    .asSequence()
    .flatMap { it.userIds.asSequence() }
    .distinct()
    .toList()

private fun mergeRecentUserIds(
    localPromotedRecentUserIds: List<Int>,
    storeRecentUserIds: List<Int>,
): List<Int> = (localPromotedRecentUserIds + storeRecentUserIds).distinct()

class ChooseParticipantsViewModel internal constructor(
    private val activeStore: Flow<ZulipStore?>,
    private val observationScope: CoroutineScope? = null,
) : LightViewModel<Unit>() {
    constructor(globalStore: GlobalStore) : this(globalStore.activeStore)

    private val _uiState = MutableStateFlow(
        ChooseParticipantsUiState(),
    )
    val uiState: StateFlow<ChooseParticipantsUiState> = _uiState.asStateFlow()
    private var observerJob: Job? = null
    private var storeRecentUserIds: List<Int> = emptyList()
    private var localPromotedRecentUserIds: List<Int> = emptyList()

    init {
        (observationScope ?: viewModelScope).launch {
            activeStore.collect { store ->
                observerJob?.cancel()
                if (store == null) {
                    storeRecentUserIds = emptyList()
                    localPromotedRecentUserIds = emptyList()
                    _uiState.value = ChooseParticipantsUiState()
                    return@collect
                }
                storeRecentUserIds = recentUserIdsFromConversations(store.recentDirectMessages.value)
                localPromotedRecentUserIds = emptyList()
                _uiState.value = ChooseParticipantsUiState(
                    users = store.selectableUsersFlow.value,
                    recentUserIds = storeRecentUserIds,
                )
                observerJob = (observationScope ?: viewModelScope).launch {
                    launch {
                        store.selectableUsersFlow.collect { users ->
                            _uiState.update { state -> state.copy(users = users) }
                        }
                    }
                    launch {
                        store.recentDirectMessages.collect { conversations ->
                            storeRecentUserIds = recentUserIdsFromConversations(conversations)
                            _uiState.update { state ->
                                state.copy(
                                    recentUserIds = mergeRecentUserIds(
                                        localPromotedRecentUserIds = localPromotedRecentUserIds,
                                        storeRecentUserIds = storeRecentUserIds,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun activateInput() {
        _uiState.update { state ->
            state.fromPickerState(state.toPickerState().activateInput())
        }
    }

    fun hideKeyboard() {
        _uiState.update { state ->
            state.fromPickerState(state.toPickerState().hideKeyboard())
        }
    }

    fun updateQuery(value: String) {
        _uiState.update { state ->
            state.fromPickerState(state.toPickerState().updateQuery(value))
        }
    }

    fun addParticipant(userId: Int) {
        if (_uiState.value.selectedUserIds.contains(userId)) return
        localPromotedRecentUserIds = listOf(userId) + localPromotedRecentUserIds.filterNot { it == userId }
        _uiState.update { state ->
            state.fromPickerState(state.toPickerState().addParticipant(userId)).copy(
                recentUserIds = mergeRecentUserIds(
                    localPromotedRecentUserIds = localPromotedRecentUserIds,
                    storeRecentUserIds = storeRecentUserIds,
                ),
            )
        }
    }

    fun removeParticipant(userId: Int) {
        _uiState.update { state ->
            state.fromPickerState(state.toPickerState().removeParticipant(userId))
        }
    }

    fun clearParticipants() {
        _uiState.update { state ->
            state.fromPickerState(state.toPickerState().clearParticipants())
        }
    }
}
