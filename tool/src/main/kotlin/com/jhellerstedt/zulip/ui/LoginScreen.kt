package com.jhellerstedt.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.jhellerstedt.zulip.ZulipServices

enum class LoginMode {
    ServerUrl,
    Password,
    ApiKey,
}

class LoginScreen(
    sealedActivity: SealedLightActivity,
    private val initialMode: LoginMode,
) : LightScreen<Unit, LoginViewModel>(sealedActivity) {

    override val viewModelClass: Class<LoginViewModel>
        get() = LoginViewModel::class.java

    override fun createViewModel(): LoginViewModel =
        LoginViewModel(ZulipServices.globalStore(lightContext), initialMode)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state.navigateHome) {
            if (state.navigateHome) {
                viewModel.consumeNavigateHome()
                goBack()
                navigateTo(screenFactory = { ZulipHomeScreen(it) })
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                key(state.editorSession) {
                    val textFieldState = rememberTextFieldState("")
                    when (state.mode) {
                    LoginMode.ServerUrl -> {
                        LightTextInputEditor(
                            title = "Server URL",
                            editorKey = state.editorSession,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            state = textFieldState,
                            onSubmit = { viewModel.submitServerUrl(it.toString()) },
                            onBack = { goBack() },
                            submitIcon = LightIcons.ARROW_RIGHT,
                            showBackButton = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    LoginMode.Password -> {
                        val title = when (state.passwordStep) {
                            PasswordStep.Email -> "Email"
                            PasswordStep.Password -> "Password"
                        }
                        LightTextInputEditor(
                            title = title,
                            editorKey = state.editorSession,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            state = textFieldState,
                            onSubmit = { value ->
                                when (state.passwordStep) {
                                    PasswordStep.Email -> viewModel.submitEmail(value.toString())
                                    PasswordStep.Password -> viewModel.submitPassword(value.toString())
                                }
                            },
                            onBack = viewModel::backToServerUrl,
                            submitIcon = LightIcons.ARROW_RIGHT,
                            showBackButton = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    LoginMode.ApiKey -> {
                        LightTextInputEditor(
                                title = if (state.pendingEmail.isBlank()) "Email" else "API Key",
                                editorKey = state.editorSession,
                                keyboardOptionsFlow = keyboardOptionsFlow,
                                state = textFieldState,
                                onSubmit = {
                                    if (state.pendingEmail.isBlank()) {
                                        viewModel.submitEmail(it.toString())
                                    } else {
                                        viewModel.submitApiKey(it.toString())
                                    }
                                },
                                onBack = viewModel::backToServerUrl,
                                submitIcon = LightIcons.ARROW_RIGHT,
                                showBackButton = false,
                                modifier = Modifier.fillMaxSize(),
                            )
                    }
                }
                }

                if (state.isLoading) {
                    LightText(
                        text = "Connecting…",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(1f.gridUnitsAsDp()),
                    )
                }

                state.errorMessage?.let { message ->
                    com.thelightphone.sdk.ui.LightFullscreenModal(
                        message = message,
                        onClose = viewModel::dismissError,
                    )
                }
            }
        }
    }
}
