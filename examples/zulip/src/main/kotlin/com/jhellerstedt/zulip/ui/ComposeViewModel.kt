package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.jhellerstedt.zulip.api.models.ZulipStream
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.PendingUploadAttachment
import com.jhellerstedt.zulip.store.ZulipStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ComposeUiState(
    val title: String = "Compose",
    val editorSession: Int = 0,
    val mentionableUsers: List<ZulipUser> = emptyList(),
    val mentionTokenAccountId: Long? = null,
    val attachments: List<ComposeAttachmentDraft> = emptyList(),
    val isSending: Boolean = false,
    val sent: Boolean = false,
    val errorMessage: String? = null,
)

class ComposeViewModel internal constructor(
    private val activeStore: StateFlow<ZulipStore?>,
    private val narrow: Narrow,
    private val observationScope: CoroutineScope? = null,
    private val sendMessageWithStore: suspend (
        ZulipStore,
        Narrow,
        String,
        List<PendingUploadAttachment>,
    ) -> Result<Int> = { store, currentNarrow, content, attachments ->
        store.send(
            narrow = currentNarrow,
            content = content,
            attachments = attachments,
        )
    },
) : LightViewModel<Unit>() {
    constructor(globalStore: GlobalStore, narrow: Narrow) : this(globalStore.activeStore, narrow)

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()
    private var composeAccountId: Long? = activeStore.value?.account?.id

    init {
        (observationScope ?: viewModelScope).launch {
            activeStore.collectLatest { store ->
                if (store == null) {
                    applyStoreSnapshot(
                        ComposeStoreSnapshot(
                            title = composeTitle(store = null, streamNames = emptyMap()),
                            mentionableUsers = emptyList(),
                            mentionTokenAccountId = null,
                        ),
                    )
                } else {
                    combine(store.selectableUsersFlow, store.streamsList) { users, streams ->
                        ComposeStoreSnapshot(
                            title = composeTitle(
                                store = store,
                                streamNames = streams.associate { it.streamId to it.name },
                            ),
                            mentionableUsers = users,
                            mentionTokenAccountId = store.account.id,
                        )
                    }.collect { snapshot ->
                        applyStoreSnapshot(snapshot)
                    }
                }
            }
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
        val store = activeStore.value
        if (store == null) {
            _uiState.update { it.copy(errorMessage = "No active account") }
            return
        }
        if (composeAccountId == null || store.account.id != composeAccountId) {
            _uiState.update { it.copy(errorMessage = "Active account changed. Reopen compose to send.") }
            return
        }
        _uiState.update { it.copy(isSending = true, errorMessage = null) }
        (observationScope ?: viewModelScope).launch {
            withContext(Dispatchers.IO) {
                val pendingAttachments = attachments.map { attachment ->
                    PendingUploadAttachment(
                        displayName = attachment.displayName,
                        mimeType = attachment.mimeType,
                        file = File(attachment.filePath),
                    )
                }
                sendMessageWithStore(
                    store,
                    narrow,
                    trimmed,
                    pendingAttachments,
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

    private fun applyStoreSnapshot(snapshot: ComposeStoreSnapshot) {
        if (composeAccountId == null && snapshot.mentionTokenAccountId != null) {
            composeAccountId = snapshot.mentionTokenAccountId
        }
        _uiState.update {
            it.copy(
                title = snapshot.title,
                mentionableUsers = snapshot.mentionableUsers,
                mentionTokenAccountId = snapshot.mentionTokenAccountId,
            )
        }
    }

    private fun composeTitle(
        store: ZulipStore?,
        streamNames: Map<Int, String>,
    ): String {
        val userNames = when (narrow) {
            is Narrow.Dm -> narrow.userIds.mapNotNull { id ->
                store?.userName(id)?.let { id to it }
            }.toMap()
            else -> emptyMap()
        }
        return "Compose: ${narrow.displayTitle(streamNames, userNames)}"
    }
}

private data class ComposeStoreSnapshot(
    val title: String,
    val mentionableUsers: List<ZulipUser>,
    val mentionTokenAccountId: Long?,
)
