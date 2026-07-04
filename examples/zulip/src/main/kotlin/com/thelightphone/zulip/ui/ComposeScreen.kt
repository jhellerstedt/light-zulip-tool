package com.thelightphone.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.zulip.ZulipServices
import com.thelightphone.zulip.store.Narrow

class ComposeScreen(
    sealedActivity: SealedLightActivity,
    private val narrow: Narrow,
) : LightScreen<Unit, ComposeViewModel>(sealedActivity) {

    override val viewModelClass: Class<ComposeViewModel>
        get() = ComposeViewModel::class.java

    override fun createViewModel(): ComposeViewModel =
        ComposeViewModel(ZulipServices.globalStore(lightContext), narrow)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state.sent) {
            if (state.sent) {
                viewModel.consumeSent()
                goBack()
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTextInputEditor(
                    title = state.title,
                    editorKey = state.editorSession,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    state = textFieldState,
                    onSubmit = { viewModel.sendMessage(it.toString()) },
                    onBack = { goBack() },
                    submitIcon = LightIcons.COMPOSE_MESSAGE,
                    showBackButton = true,
                    modifier = Modifier.fillMaxSize(),
                )

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
