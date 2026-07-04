package com.thelightphone.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.zulip.api.models.ZulipStream
import com.thelightphone.zulip.store.GlobalStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StreamListViewModel(
    private val globalStore: GlobalStore,
) : LightViewModel<Unit>() {
    private val _streams = MutableStateFlow<List<ZulipStream>>(emptyList())
    val streams: StateFlow<List<ZulipStream>> = _streams.asStateFlow()
    private var observerJob: Job? = null

    init {
        viewModelScope.launch {
            globalStore.activeStore.collect { store ->
                observerJob?.cancel()
                if (store == null) {
                    _streams.value = emptyList()
                    return@collect
                }
                observerJob = viewModelScope.launch {
                    store.streamsList.collect { streamList ->
                        _streams.value = streamList
                    }
                }
            }
        }
    }
}
