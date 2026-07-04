package com.thelightphone.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.zulip.api.models.ZulipMessage
import com.thelightphone.zulip.store.GlobalStore
import com.thelightphone.zulip.store.Narrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MessageListUiState(
    val title: String = "",
    val messages: List<ZulipMessage> = emptyList(),
    val authorNamesByMessageId: Map<Int, String> = emptyMap(),
    val initialScrollIndex: Int = -1,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class MessageListViewModel(
    private val globalStore: GlobalStore,
    private val narrow: Narrow,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(MessageListUiState())
    val uiState: StateFlow<MessageListUiState> = _uiState.asStateFlow()

    init {
        val store = globalStore.activeStore.value
        val streamNames = store?.streamsList?.value?.associate { it.streamId to it.name }.orEmpty()
        val userNames = when (narrow) {
            is Narrow.Dm -> narrow.userIds.mapNotNull { id ->
                store?.userName(id)?.let { id to it }
            }.toMap()
            else -> emptyMap()
        }
        _uiState.update {
            it.copy(title = narrow.displayTitle(streamNames, userNames))
        }
    }

    fun loadMessages() {
        val store = globalStore.activeStore.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            withContext(Dispatchers.IO) {
                store.loadMessages(narrow)
            }.onSuccess { messages ->
                val streamNames = store.streamsList.value.associate { it.streamId to it.name }
                val allUserNames = store.userNames()
                val titleUserNames = when (narrow) {
                    is Narrow.Dm -> narrow.userIds.mapNotNull { id ->
                        store.userName(id)?.let { id to it }
                    }.toMap()
                    else -> emptyMap()
                }
                _uiState.update {
                    it.copy(
                        messages = messages,
                        authorNamesByMessageId = messages.associate { message ->
                            message.id to messageAuthorName(message, allUserNames)
                        },
                        initialScrollIndex = initialMessageScrollIndex(messages),
                        title = narrow.displayTitle(streamNames, titleUserNames),
                        isLoading = false,
                    )
                }
                withContext(Dispatchers.IO) {
                    store.markNarrowRead(narrow)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to load messages",
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
