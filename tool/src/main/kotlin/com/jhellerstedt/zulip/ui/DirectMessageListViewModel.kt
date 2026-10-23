package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.Narrow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DirectMessageRow(
    val title: String,
    val narrow: Narrow.Dm,
)

class DirectMessageListViewModel(
    private val globalStore: GlobalStore,
) : ZulipViewModel<Unit>(globalStore) {
    private val _rows = MutableStateFlow<List<DirectMessageRow>>(emptyList())
    val rows: StateFlow<List<DirectMessageRow>> = _rows.asStateFlow()

    private var observerJob: Job? = null

    init {
        viewModelScope.launch {
            globalStore.activeStore.collect { store ->
                observerJob?.cancel()
                if (store == null) {
                    _rows.value = emptyList()
                    return@collect
                }
                observerJob = viewModelScope.launch {
                    store.recentDirectMessages.collect { conversations ->
                        _rows.value = conversations.map { conversation ->
                            val narrow = Narrow.Dm(conversation.userIds)
                            val userNames = conversation.userIds.mapNotNull { id ->
                                store.userName(id)?.let { id to it }
                            }.toMap()
                            DirectMessageRow(
                                title = narrow.displayTitle(emptyMap(), userNames),
                                narrow = narrow,
                            )
                        }
                    }
                }
            }
        }
    }
}
