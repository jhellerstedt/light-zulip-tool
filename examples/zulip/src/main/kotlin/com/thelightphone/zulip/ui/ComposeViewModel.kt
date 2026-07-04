package com.thelightphone.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.zulip.store.GlobalStore
import com.thelightphone.zulip.store.Narrow
import com.thelightphone.zulip.store.PendingUploadAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ComposeUiState(
    val title: String = "Compose",
    val editorSession: Int = 0,
    val attachments: List<ComposeAttachmentDraft> = emptyList(),
    val isSending: Boolean = false,
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

    fun replaceAttachments(attachments: List<ComposeAttachmentDraft>) {
        _uiState.update { it.copy(attachments = attachments) }
    }

    fun sendMessage(content: String) {
        val trimmed = content.trim()
        val attachments = _uiState.value.attachments.sortedBy { it.sourceIndex }
        if (trimmed.isBlank() && attachments.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Message cannot be empty") }
            return
        }
        val store = globalStore.activeStore.value
        if (store == null) {
            _uiState.update { it.copy(errorMessage = "No active account") }
            return
        }
        _uiState.update { it.copy(isSending = true, errorMessage = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.send(
                    narrow = narrow,
                    content = trimmed,
                    attachments = attachments.map { attachment ->
                        PendingUploadAttachment(
                            displayName = attachment.displayName,
                            mimeType = attachment.mimeType,
                            file = File(attachment.filePath),
                        )
                    },
                )
            }.onSuccess {
                _uiState.update { it.copy(isSending = false, sent = true) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSending = false,
                        errorMessage = error.message ?: "Failed to send message",
                    )
                }
            }
        }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun consumeSent() {
        _uiState.update { it.copy(sent = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
