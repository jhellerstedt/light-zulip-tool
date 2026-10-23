package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.SimpleLightScreen
import com.jhellerstedt.zulip.TOOL_DISPLAY_NAME
import com.jhellerstedt.zulip.data.PendingMessageEntity
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.GlobalStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ZulipHomeUiState(
    val realmTitle: String = TOOL_DISPLAY_NAME,
    val streamCount: Int = 0,
    val directMessageCount: Int = 0,
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
                        it.copy(streamCount = 0, directMessageCount = 0, isLoading = false)
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
