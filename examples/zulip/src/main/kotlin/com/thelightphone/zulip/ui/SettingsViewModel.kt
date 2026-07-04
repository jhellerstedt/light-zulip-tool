package com.thelightphone.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.zulip.data.StoredZulipAccount
import com.thelightphone.zulip.store.GlobalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val activeAccount: StoredZulipAccount? = null,
    val navigateChooseAccount: Boolean = false,
)

class SettingsViewModel(
    private val globalStore: GlobalStore,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            globalStore.activeAccount.collect { account ->
                _uiState.update { it.copy(activeAccount = account) }
            }
        }
    }

    fun openChooseAccount() {
        _uiState.update { it.copy(navigateChooseAccount = true) }
    }

    fun consumeNavigateChooseAccount() {
        _uiState.update { it.copy(navigateChooseAccount = false) }
    }

    fun removeActiveAccount() {
        val accountId = globalStore.activeAccount.value?.id ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                globalStore.removeAccount(accountId)
            }
            _uiState.update { it.copy(navigateChooseAccount = true) }
        }
    }
}
