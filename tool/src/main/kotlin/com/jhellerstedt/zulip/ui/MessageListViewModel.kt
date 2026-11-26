package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.ZulipStore
import com.jhellerstedt.zulip.util.ReactionEmoji
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import com.jhellerstedt.zulip.util.reactorId
import com.jhellerstedt.zulip.util.replacingOwnReaction
import com.jhellerstedt.zulip.util.withoutOwnReactions
import kotlinx.coroutines.launch

data class MessageListUiState(
    val title: String = "",
    val messages: List<ZulipMessage> = emptyList(),
    val authorNamesByMessageId: Map<Int, String> = emptyMap(),
    val initialScrollIndex: Int = -1,
    val isRefreshing: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.REFRESHING,
    val errorMessage: String? = null,
    val ownUserId: Int = 0,
    val userNames: Map<Int, String> = emptyMap(),
    val reactionErrorMessageId: Int? = null,
)

class MessageListViewModel internal constructor(
    private val narrow: Narrow,
    private val cachedMessages: suspend () -> List<ZulipMessage>,
    private val refreshMessages: suspend () -> Result<List<ZulipMessage>>,
    private val markRead: suspend () -> Unit,
    private val storeSnapshot: () -> ZulipStore? = { null },
    private val connectionState: () -> ConnectionState? = { null },
    private val sendReaction: suspend (Int, ReactionEmoji) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    private val clearReaction: suspend (Int) -> Result<Unit> = { Result.success(Unit) },
    messagesRevision: Flow<Int> = emptyFlow(),
    pauseEventSync: () -> Unit = {},
    resumeEventSync: () -> Unit = {},
) : ZulipViewModel<Unit>(pauseEventSync, resumeEventSync) {
    constructor(globalStore: GlobalStore, narrow: Narrow) : this(
        narrow = narrow,
        cachedMessages = { globalStore.loadMessages(narrow) },
        refreshMessages = { globalStore.refreshMessages(narrow) },
        markRead = { globalStore.markNarrowRead(narrow) },
        storeSnapshot = { globalStore.activeStore.value },
        connectionState = { globalStore.activeStore.value?.connectionState?.value },
        sendReaction = { messageId, emoji -> globalStore.setReaction(messageId, emoji) },
        clearReaction = { messageId -> globalStore.removeOwnReaction(messageId) },
        messagesRevision = globalStore.activeStore.value?.messagesRevision ?: emptyFlow(),
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
            it.copy(
                title = narrow.displayTitle(streamNames, userNames),
                ownUserId = store?.account?.userId ?: it.ownUserId,
                userNames = store?.userNames().orEmpty(),
            )
        }
        viewModelScope.launch {
            messagesRevision.collect {
                val latest = storeSnapshot()?.getMessagesFor(narrow) ?: return@collect
                if (latest.isEmpty() && _uiState.value.messages.isEmpty()) return@collect
                publishMessages(latest)
            }
        }
    }

    fun setReaction(messageId: Int, emoji: ReactionEmoji) {
        viewModelScope.launch {
            _uiState.update { it.copy(reactionErrorMessageId = null) }
            sendReaction(messageId, emoji)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { message ->
                                if (message.id == messageId) {
                                    message.replacingOwnReaction(state.ownUserId, emoji)
                                } else {
                                    message
                                }
                            },
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state -> state.copy(reactionErrorMessageId = messageId) }
                }
        }
    }

    fun removeReaction(messageId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(reactionErrorMessageId = null) }
            clearReaction(messageId)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { message ->
                                if (message.id == messageId) message.withoutOwnReactions(state.ownUserId) else message
                            },
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state -> state.copy(reactionErrorMessageId = messageId) }
                }
        }
    }

    fun toggleLike(messageId: Int) {
        val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        val own = message.reactions.filter { it.reactorId() == _uiState.value.ownUserId }
        val heart = ReactionEmoji.like
        if (own.size == 1 && own.single().emojiCode.equals(heart.code, ignoreCase = true)) {
            removeReaction(messageId)
        } else {
            setReaction(messageId, heart)
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
                    if (cached.isNotEmpty()) {
                        markRead()
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
                ownUserId = store?.account?.userId ?: it.ownUserId,
                userNames = allUserNames.ifEmpty { it.userNames },
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
