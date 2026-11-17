package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.SimpleLightScreen
import com.jhellerstedt.zulip.TOOL_DISPLAY_NAME
import com.jhellerstedt.zulip.data.PendingMessageEntity
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.cacheKey
import com.jhellerstedt.zulip.store.hasDirectUnread
import com.jhellerstedt.zulip.store.hasStreamUnread
import com.jhellerstedt.zulip.store.unreadStreamIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeStreamShortcut(
    val streamId: Int,
    val name: String,
    val unread: Boolean,
)

data class HomeDirectShortcut(
    val title: String,
    val narrow: Narrow.Dm,
    val unread: Boolean,
)

data class ZulipHomeUiState(
    val realmTitle: String = TOOL_DISPLAY_NAME,
    val streamCount: Int = 0,
    val streamsHaveUnread: Boolean = false,
    val directMessageCount: Int = 0,
    val directMessagesHaveUnread: Boolean = false,
    val recentStreams: List<HomeStreamShortcut> = emptyList(),
    val recentDirectMessages: List<HomeDirectShortcut> = emptyList(),
    val showHome: Boolean = false,
    val accountCount: Int? = null,
    val isLoading: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.REFRESHING,
    val pendingMessageCount: Int = 0,
    val failedMessage: PendingMessageEntity? = null,
    val errorMessage: String? = null,
)

class ZulipHomeViewModel(
    private val globalStore: GlobalStore,
) : ZulipViewModel<Unit>(globalStore) {
    private val _uiState = MutableStateFlow(ZulipHomeUiState())
    val uiState: StateFlow<ZulipHomeUiState> = _uiState.asStateFlow()
    private var storeObserverJob: Job? = null

    // A dismissed failure is hidden from the overlay until either it is resolved
    // (retried/discarded) or a different failure takes its place.
    private val _dismissedFailureId = MutableStateFlow<String?>(null)

    init {
        refreshAccountCount()
        viewModelScope.launch {
            globalStore.activeAccount.collect { account ->
                _uiState.update {
                    it.copy(realmTitle = account?.realmName?.ifBlank { account.realmUrl } ?: TOOL_DISPLAY_NAME)
                }
            }
        }
        viewModelScope.launch {
            globalStore.activeStore.collect { store ->
                storeObserverJob?.cancel()
                if (store == null) {
                    _uiState.update {
                        it.copy(
                            streamCount = 0,
                            directMessageCount = 0,
                            isLoading = false,
                            recentStreams = emptyList(),
                            recentDirectMessages = emptyList(),
                        )
                    }
                    return@collect
                }
                storeObserverJob = viewModelScope.launch {
                    launch {
                        store.streamsList.collect { streams ->
                            _uiState.update { it.copy(streamCount = streams.size) }
                        }
                    }
                    launch {
                        store.recentDirectMessages.collect { conversations ->
                            _uiState.update { it.copy(directMessageCount = conversations.size) }
                        }
                    }
                    launch {
                        store.isLoading.collect { loading ->
                            _uiState.update { it.copy(isLoading = loading) }
                        }
                    }
                    launch {
                        store.connectionState.collect { connectionState ->
                            _uiState.update { it.copy(connectionState = connectionState) }
                        }
                    }
                    launch {
                        store.error.collect { error ->
                            if (error != null) {
                                _uiState.update { it.copy(errorMessage = error) }
                            }
                        }
                    }
                    launch {
                        store.unreadCounts.collect { counts ->
                            _uiState.update {
                                it.copy(
                                    streamsHaveUnread = counts.hasStreamUnread(),
                                    directMessagesHaveUnread = counts.hasDirectUnread(),
                                )
                            }
                        }
                    }
                    launch {
                        combine(
                            store.streamsList,
                            store.recentDirectMessages,
                            store.unreadCounts,
                        ) { streams, conversations, unread ->
                            val unreadStreamIds = unread.unreadStreamIds()
                            val recentStreams = streams.take(2).map { stream ->
                                HomeStreamShortcut(
                                    streamId = stream.streamId,
                                    name = stream.name,
                                    unread = stream.streamId in unreadStreamIds,
                                )
                            }
                            val recentDirectMessages = conversations.take(2).map { conversation ->
                                val narrow = Narrow.Dm(conversation.userIds)
                                val userNames = conversation.userIds.mapNotNull { id ->
                                    store.userName(id)?.let { id to it }
                                }.toMap()
                                HomeDirectShortcut(
                                    title = narrow.displayTitle(emptyMap(), userNames),
                                    narrow = narrow,
                                    unread = (unread[narrow.cacheKey()] ?: 0) > 0,
                                )
                            }
                            recentStreams to recentDirectMessages
                        }.collect { (recentStreams, recentDirectMessages) ->
                            _uiState.update {
                                it.copy(
                                    recentStreams = recentStreams,
                                    recentDirectMessages = recentDirectMessages,
                                )
                            }
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            globalStore.activeAccount.flatMapLatest { account ->
                account?.let { globalStore.outboxSummary(it.id) } ?: flowOf(null)
            }.combine(_dismissedFailureId) { summary, dismissedId ->
                summary to dismissedId
            }.collect { (summary, dismissedId) ->
                _uiState.update {
                    it.copy(
                        pendingMessageCount = summary?.pendingCount ?: 0,
                        failedMessage = summary?.firstFailure?.takeIf { failure -> failure.localId != dismissedId },
                    )
                }
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refreshFromStore()
        refreshAccountCount()
    }

    private fun refreshAccountCount() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching { globalStore.listAccounts().size }.getOrNull()
            } ?: return@launch
            _uiState.update {
                it.copy(
                    accountCount = count,
                    showHome = homeDestination(count) != HomeDestination.Menu,
                )
            }
        }
    }

    private fun refreshFromStore() {
        val store = globalStore.activeStore.value
        _uiState.update {
            it.copy(
                realmTitle = globalStore.activeAccount.value?.realmName ?: TOOL_DISPLAY_NAME,
                streamCount = store?.streamsList?.value?.size ?: 0,
                directMessageCount = store?.recentDirectMessages?.value?.size ?: 0,
            )
        }
    }

    fun dismissError() {
        globalStore.activeStore.value?.clearError()
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun retryFailedMessage() {
        val message = _uiState.value.failedMessage ?: return
        val accountId = globalStore.activeAccount.value?.id ?: return
        viewModelScope.launch {
            globalStore.retryOutboxMessage(message.localId, accountId)
        }
    }

    fun discardFailedMessage() {
        val message = _uiState.value.failedMessage ?: return
        viewModelScope.launch {
            globalStore.discardOutboxMessage(message.localId)
        }
    }

    fun closeFailedMessage() {
        val message = _uiState.value.failedMessage ?: return
        _dismissedFailureId.value = message.localId
    }
}
