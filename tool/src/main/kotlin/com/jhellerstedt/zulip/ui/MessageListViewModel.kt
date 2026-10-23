package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.ZulipStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MessageListUiState(
    val title: String = "",
    val messages: List<ZulipMessage> = emptyList(),
    val authorNamesByMessageId: Map<Int, String> = emptyMap(),
    val initialScrollIndex: Int = -1,
    val isRefreshing: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.REFRESHING,
    val errorMessage: String? = null,
)

class MessageListViewModel internal constructor(
    private val narrow: Narrow,
    private val cachedMessages: suspend () -> List<ZulipMessage>,
    private val refreshMessages: suspend () -> Result<List<ZulipMessage>>,
    private val markRead: suspend () -> Unit,
    private val storeSnapshot: () -> ZulipStore? = { null },
    private val connectionState: () -> ConnectionState? = { null },
    pauseEventSync: () -> Unit = {},
    resumeEventSync: () -> Unit = {},
) : ZulipViewModel<Unit>(pauseEventSync, resumeEventSync) {
    constructor(globalStore: GlobalStore, narrow: Narrow) : this(
        narrow = narrow,
        cachedMessages = { globalStore.loadMessages(narrow) },
        refreshMessages = { globalStore.refreshMessages(narrow) },
        markRead = { globalStore.activeStore.value?.markNarrowRead(narrow) },
        storeSnapshot = { globalStore.activeStore.value },
        connectionState = { globalStore.activeStore.value?.connectionState?.value },
        pauseEventSync = globalStore::pauseEventSync,
        resumeEventSync = globalStore::resumeEventSync,
    )

    private val _uiState = MutableStateFlow(MessageListUiState())
    val uiState: StateFlow<MessageListUiState> = _uiState.asStateFlow()

    init {
        val store = storeSnapshot()
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
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            val cached = cachedMessages()
            publishMessages(cached)
            refreshMessages()
                .onSuccess { messages ->
                    publishMessages(messages)
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            connectionState = ConnectionState.ONLINE,
                        )
                    }
                    markRead()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            connectionState = connectionState() ?: ConnectionState.OFFLINE,
                            errorMessage = if (cached.isEmpty()) {
                                error.message ?: "Failed to load messages"
                            } else {
                                null
                            },
                        )
                    }
                }
        }
    }

    private fun publishMessages(messages: List<ZulipMessage>) {
        val store = storeSnapshot()
        val streamNames = store?.streamsList?.value?.associate { it.streamId to it.name }.orEmpty()
        val allUserNames = store?.userNames().orEmpty()
        val titleUserNames = when (narrow) {
            is Narrow.Dm -> narrow.userIds.mapNotNull { id ->
                store?.userName(id)?.let { id to it }
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
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
