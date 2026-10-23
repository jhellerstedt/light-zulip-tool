package com.jhellerstedt.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.jhellerstedt.zulip.TOOL_DISPLAY_NAME
import com.jhellerstedt.zulip.ZulipServices

@InitialScreen
class ChooseAccountScreen : LightScreen<Unit, ChooseAccountViewModel> {
    private val autoRestoreActiveAccount: Boolean

    constructor(sealedActivity: SealedLightActivity) : this(sealedActivity, true)

    constructor(
        sealedActivity: SealedLightActivity,
        autoRestoreActiveAccount: Boolean,
    ) : super(sealedActivity) {
        this.autoRestoreActiveAccount = autoRestoreActiveAccount
    }

    override val viewModelClass: Class<ChooseAccountViewModel>
        get() = ChooseAccountViewModel::class.java

    override fun createViewModel(): ChooseAccountViewModel {
        return ChooseAccountViewModel(
            ZulipServices.globalStore(lightContext),
            autoRestoreActiveAccount,
        )
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.initialize()
        }

        LaunchedEffect(state.navigateHome) {
            if (state.navigateHome) {
                viewModel.consumeNavigateHome()
                if (autoRestoreActiveAccount) {
                    navigateTo(screenFactory = { ZulipHomeScreen(it) })
                } else {
                    goBack()
                }
            }
        }

        LaunchedEffect(state.navigateLogin) {
            if (state.navigateLogin) {
                viewModel.consumeNavigateLogin()
                navigateTo(screenFactory = { LoginScreen(it, LoginMode.ServerUrl) })
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = if (autoRestoreActiveAccount) {
                            null
                        } else {
                            LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            )
                        },
                        center = LightTopBarCenter.Text(TOOL_DISPLAY_NAME),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    when {
                        state.isLoading -> {
                            LightText(
                                text = "Loading…",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                            )
                        }

                        state.accounts.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(
                                    text = "No accounts added",
                                    variant = LightTextVariant.Copy,
                                    align = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                                )
                            }
                        }

                        else -> {
                            LightScrollView(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 1f.gridUnitsAsDp()),
                            ) {
                                state.accounts.forEach { account ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .lightClickable { viewModel.selectAccount(account.id) }
                                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                                    ) {
                                        LightText(
                                            text = account.realmName.ifBlank { account.realmUrl },
                                            variant = LightTextVariant.Heading,
                                        )
                                        LightText(
                                            text = account.email,
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    LightBottomBar(
                        items = listOf(
                            LightBarButton.Text(
                                text = "ADD",
                                onClick = viewModel::startAddAccount,
                            ),
                            null,
                            null,
                        ),
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
