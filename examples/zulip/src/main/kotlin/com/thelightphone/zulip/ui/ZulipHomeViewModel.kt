package com.thelightphone.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.zulip.store.GlobalStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ZulipHomeUiState(
    val realmTitle: String = "Zulip",
    val streamCount: Int = 0,
    val directMessageCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class ZulipHomeViewModel(
    private val globalStore: GlobalStore,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(ZulipHomeUiState())
    val uiState: StateFlow<ZulipHomeUiState> = _uiState.asStateFlow()
    private var storeObserverJob: Job? = null

    init {
        viewModelScope.launch {
            globalStore.activeAccount.collect { account ->
                _uiState.update {
                    it.copy(realmTitle = account?.realmName?.ifBlank { account.realmUrl } ?: "Zulip")
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
                        store.error.collect { error ->
                            if (error != null) {
                                _uiState.update { it.copy(errorMessage = error) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        refreshFromStore()
    }

    private fun refreshFromStore() {
        val store = globalStore.activeStore.value
        _uiState.update {
            it.copy(
                realmTitle = globalStore.activeAccount.value?.realmName ?: "Zulip",
                streamCount = store?.streamsList?.value?.size ?: 0,
                directMessageCount = store?.recentDirectMessages?.value?.size ?: 0,
            )
        }
    }

    fun dismissError() {
        globalStore.activeStore.value?.clearError()
        _uiState.update { it.copy(errorMessage = null) }
    }
}
