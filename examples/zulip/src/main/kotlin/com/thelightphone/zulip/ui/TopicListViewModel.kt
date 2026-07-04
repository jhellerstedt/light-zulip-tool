package com.thelightphone.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.zulip.api.models.ZulipTopic
import com.thelightphone.zulip.store.GlobalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TopicListUiState(
    val title: String = "",
    val topics: List<ZulipTopic> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class TopicListViewModel(
    private val globalStore: GlobalStore,
    private val streamId: Int,
    streamName: String,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(TopicListUiState(title = streamName))
    val uiState: StateFlow<TopicListUiState> = _uiState.asStateFlow()

    fun loadTopics() {
        val store = globalStore.activeStore.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            withContext(Dispatchers.IO) {
                store.loadTopics(streamId)
            }.onSuccess { topics ->
                _uiState.update { it.copy(topics = topics, isLoading = false) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to load topics",
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
