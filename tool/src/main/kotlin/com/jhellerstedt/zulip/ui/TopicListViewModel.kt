package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.jhellerstedt.zulip.api.models.ZulipTopic
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.unreadTopicNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TopicListUiState(
    val title: String = "",
    val topics: List<ZulipTopic> = emptyList(),
    val unreadTopicNames: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.REFRESHING,
    val errorMessage: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TopicListViewModel internal constructor(
    private val streamId: Int,
    streamName: String,
    private val cachedTopics: suspend () -> List<ZulipTopic>,
    private val refreshTopics: suspend () -> Result<List<ZulipTopic>>,
    private val connectionState: () -> ConnectionState? = { null },
    unreadTopicNames: Flow<Set<String>> = flowOf(emptySet()),
    pauseEventSync: () -> Unit = {},
    resumeEventSync: () -> Unit = {},
) : ZulipViewModel<Unit>(pauseEventSync, resumeEventSync) {
    constructor(globalStore: GlobalStore, streamId: Int, streamName: String) : this(
        streamId = streamId,
        streamName = streamName,
        cachedTopics = { globalStore.loadTopics(streamId) },
        refreshTopics = { globalStore.refreshTopics(streamId) },
        connectionState = { globalStore.activeStore.value?.connectionState?.value },
        unreadTopicNames = globalStore.activeStore.flatMapLatest { store ->
            store?.unreadCounts?.map { counts -> counts.unreadTopicNames(streamId) } ?: flowOf(emptySet())
        },
        pauseEventSync = globalStore::pauseEventSync,
        resumeEventSync = globalStore::resumeEventSync,
    )

    private val _uiState = MutableStateFlow(TopicListUiState(title = streamName))
    val uiState: StateFlow<TopicListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            unreadTopicNames.collect { names ->
                _uiState.update { it.copy(unreadTopicNames = names) }
            }
        }
    }

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
