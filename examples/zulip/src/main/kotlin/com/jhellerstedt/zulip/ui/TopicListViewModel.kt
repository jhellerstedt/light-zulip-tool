package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.jhellerstedt.zulip.api.models.ZulipTopic
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.GlobalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TopicListUiState(
    val title: String = "",
    val topics: List<ZulipTopic> = emptyList(),
    val isRefreshing: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.REFRESHING,
    val errorMessage: String? = null,
)

class TopicListViewModel internal constructor(
    private val streamId: Int,
    streamName: String,
    private val cachedTopics: suspend () -> List<ZulipTopic>,
    private val refreshTopics: suspend () -> Result<List<ZulipTopic>>,
    private val connectionState: () -> ConnectionState? = { null },
    pauseEventSync: () -> Unit = {},
    resumeEventSync: () -> Unit = {},
) : ZulipViewModel<Unit>(pauseEventSync, resumeEventSync) {
    constructor(globalStore: GlobalStore, streamId: Int, streamName: String) : this(
        streamId = streamId,
        streamName = streamName,
        cachedTopics = { globalStore.loadTopics(streamId) },
        refreshTopics = { globalStore.refreshTopics(streamId) },
        connectionState = { globalStore.activeStore.value?.connectionState?.value },
        pauseEventSync = globalStore::pauseEventSync,
        resumeEventSync = globalStore::resumeEventSync,
    )

    private val _uiState = MutableStateFlow(TopicListUiState(title = streamName))
    val uiState: StateFlow<TopicListUiState> = _uiState.asStateFlow()

    fun loadTopics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            val cached = cachedTopics()
            _uiState.update { it.copy(topics = cached) }
            refreshTopics()
                .onSuccess { topics ->
                    _uiState.update {
                        it.copy(
                            topics = topics,
                            isRefreshing = false,
                            connectionState = ConnectionState.ONLINE,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            connectionState = connectionState() ?: ConnectionState.OFFLINE,
                            errorMessage = if (cached.isEmpty()) {
                                error.message ?: "Failed to load topics"
                            } else {
                                null
                            },
                        )
                    }
                }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
