package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.jhellerstedt.zulip.api.models.ZulipStream
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.unreadStreamIds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StreamListViewModel(
    private val globalStore: GlobalStore,
) : ZulipViewModel<Unit>(globalStore) {
    private val _streams = MutableStateFlow<List<ZulipStream>>(emptyList())
    val streams: StateFlow<List<ZulipStream>> = _streams.asStateFlow()
    private val _unreadStreamIds = MutableStateFlow<Set<Int>>(emptySet())
    val unreadStreamIds: StateFlow<Set<Int>> = _unreadStreamIds.asStateFlow()
    private var observerJob: Job? = null

    init {
        viewModelScope.launch {
            globalStore.activeStore.collect { store ->
                observerJob?.cancel()
                if (store == null) {
                    _streams.value = emptyList()
                    _unreadStreamIds.value = emptySet()
                    return@collect
                }
                observerJob = viewModelScope.launch {
                    launch {
                        store.streamsList.collect { streamList ->
                            _streams.value = streamList
                        }
                    }
                    launch {
                        store.unreadCounts.collect { counts ->
                            _unreadStreamIds.value = counts.unreadStreamIds()
                        }
                    }
                }
            }
        }
    }
}
