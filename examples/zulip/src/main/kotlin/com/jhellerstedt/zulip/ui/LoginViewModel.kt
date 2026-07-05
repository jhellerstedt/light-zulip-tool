package com.jhellerstedt.zulip.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.routes.fetchApiKey
import com.jhellerstedt.zulip.api.routes.fetchServerSettings
import com.jhellerstedt.zulip.store.GlobalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PasswordStep {
    Email,
    Password,
}

data class LoginUiState(
    val mode: LoginMode = LoginMode.ServerUrl,
    val passwordStep: PasswordStep = PasswordStep.Email,
    val realmUrl: String = "",
    val realmName: String = "",
    val zulipFeatureLevel: Int = 0,
    val pendingEmail: String = "",
    val pendingPassword: String = "",
    val editorSession: Int = 0,
    val isLoading: Boolean = false,
    val navigateHome: Boolean = false,
    val errorMessage: String? = null,
)

class LoginViewModel(
    private val globalStore: GlobalStore,
    initialMode: LoginMode,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(LoginUiState(mode = initialMode))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun submitServerUrl(url: String) {
        val normalized = ZulipClient.normalizeRealmUrl(url)
        if (normalized.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter a server URL") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            withContext(Dispatchers.IO) {
                runCatching {
                    val client = ZulipClient(normalized)
                    try {
                        val settings = fetchServerSettings(client)
                        settings to client
                    } finally {
                        client.close()
                    }
                }
            }.onSuccess { (settings, _) ->
                val passwordEnabled = settings.authenticationMethods?.password == true ||
                    settings.authenticationMethods?.ldap == true
                _uiState.update {
                    it.copy(
                        realmUrl = normalized,
                        realmName = settings.realmName.ifBlank { normalized },
                        zulipFeatureLevel = settings.zulipFeatureLevel,
                        mode = if (passwordEnabled) LoginMode.Password else LoginMode.ApiKey,
                        editorSession = it.editorSession + 1,
                        isLoading = false,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not reach server",
                    )
                }
            }
        }
    }

    fun submitEmail(email: String) {
        val trimmed = email.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter your email") }
            return
        }
        val state = _uiState.value
        if (state.mode == LoginMode.Password) {
            _uiState.update {
                it.copy(
                    pendingEmail = trimmed,
                    passwordStep = PasswordStep.Password,
                    editorSession = it.editorSession + 1,
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                pendingEmail = trimmed,
                editorSession = it.editorSession + 1,
            )
        }
    }

    fun submitPassword(password: String) {
        val state = _uiState.value
        if (state.pendingEmail.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter your email first") }
            return
        }
        loginWithPassword(state.pendingEmail, password)
    }

    fun submitApiKey(apiKey: String) {
        val state = _uiState.value
        val email = state.pendingEmail.trim()
        val key = apiKey.trim()
        if (email.isBlank() || key.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter email and API key") }
            return
        }
        val existing = globalStore.listAccounts().firstOrNull {
            it.realmUrl == state.realmUrl && it.email.equals(email, ignoreCase = true)
        }
        if (existing != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                withContext(Dispatchers.IO) { globalStore.switchAccount(existing.id) }
                    .onSuccess { _uiState.update { it.copy(isLoading = false, navigateHome = true) } }
                    .onFailure { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = error.message ?: "Failed to switch account",
                            )
                        }
                    }
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            withContext(Dispatchers.IO) {
                globalStore.addAccount(
                    realmUrl = state.realmUrl,
                    realmName = state.realmName,
                    email = email,
                    apiKey = key,
                    userId = 0,
                    zulipFeatureLevel = state.zulipFeatureLevel,
                )
            }.onSuccess {
                _uiState.update { it.copy(isLoading = false, navigateHome = true) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Login failed",
                    )
                }
            }
        }
    }

    private fun loginWithPassword(email: String, password: String) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            withContext(Dispatchers.IO) {
                runCatching {
                    val existing = globalStore.listAccounts().firstOrNull {
                        it.realmUrl == state.realmUrl && it.email.equals(email, ignoreCase = true)
                    }
                    if (existing != null) {
                        return@runCatching globalStore.switchAccount(existing.id).getOrThrow()
                    }
                    val client = ZulipClient(state.realmUrl)
                    try {
                        val keyResponse = fetchApiKey(client, email, password)
                        globalStore.addAccount(
                            realmUrl = state.realmUrl,
                            realmName = state.realmName,
                            email = keyResponse.email.ifBlank { email },
                            apiKey = keyResponse.apiKey,
                            userId = keyResponse.userId,
                            zulipFeatureLevel = state.zulipFeatureLevel,
                        ).getOrThrow()
                    } finally {
                        client.close()
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(isLoading = false, navigateHome = true) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Login failed",
                    )
                }
            }
        }
    }

    fun backToServerUrl() {
        _uiState.update {
            LoginUiState(
                mode = LoginMode.ServerUrl,
                editorSession = it.editorSession + 1,
            )
        }
    }

    fun consumeNavigateHome() {
        _uiState.update { it.copy(navigateHome = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
