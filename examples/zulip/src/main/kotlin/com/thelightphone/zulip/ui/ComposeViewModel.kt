package com.thelightphone.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.zulip.store.GlobalStore
import com.thelightphone.zulip.store.Narrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ComposeUiState(
    val title: String = "Compose",
    val editorSession: Int = 0,
    val sent: Boolean = false,
    val errorMessage: String? = null,
)

class ComposeViewModel(
    private val globalStore: GlobalStore,
    private val narrow: Narrow,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

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
            it.copy(title = "Compose: ${narrow.displayTitle(streamNames, userNames)}")
        }
    }

    fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Message cannot be empty") }
            return
        }
        val store = globalStore.activeStore.value
        if (store == null) {
            _uiState.update { it.copy(errorMessage = "No active account") }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.send(narrow, trimmed)
            }.onSuccess {
                _uiState.update { it.copy(sent = true) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Failed to send message")
                }
            }
        }
    }

    fun consumeSent() {
        _uiState.update { it.copy(sent = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
