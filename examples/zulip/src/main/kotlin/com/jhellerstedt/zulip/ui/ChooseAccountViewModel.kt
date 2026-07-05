package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.store.GlobalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChooseAccountUiState(
    val accounts: List<StoredZulipAccount> = emptyList(),
    val isLoading: Boolean = true,
    val navigateHome: Boolean = false,
    val navigateLogin: Boolean = false,
    val errorMessage: String? = null,
)

class ChooseAccountViewModel(
    private val globalStore: GlobalStore,
    private val autoRestoreActiveAccount: Boolean,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(ChooseAccountUiState())
    val uiState: StateFlow<ChooseAccountUiState> = _uiState.asStateFlow()

    fun initialize() {
        viewModelScope.launch {
            val accounts = withContext(Dispatchers.IO) { globalStore.listAccounts() }
            if (accounts.isEmpty()) {
                _uiState.update {
                    it.copy(
                        accounts = emptyList(),
                        isLoading = false,
                        navigateLogin = true,
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(accounts = accounts, isLoading = false) }
            if (!autoRestoreActiveAccount) {
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { globalStore.initialize() }
            }.onSuccess {
                if (globalStore.activeAccount.value != null) {
                    _uiState.update { it.copy(navigateHome = true) }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Failed to restore account")
                }
            }
        }
    }

    fun selectAccount(accountId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            withContext(Dispatchers.IO) {
                globalStore.switchAccount(accountId)
            }.onSuccess {
                _uiState.update { it.copy(isLoading = false, navigateHome = true) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to switch account",
                    )
                }
            }
        }
    }

    fun startAddAccount() {
        _uiState.update { it.copy(navigateLogin = true) }
    }

    fun consumeNavigateHome() {
        _uiState.update { it.copy(navigateHome = false) }
    }

    fun consumeNavigateLogin() {
        _uiState.update { it.copy(navigateLogin = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
