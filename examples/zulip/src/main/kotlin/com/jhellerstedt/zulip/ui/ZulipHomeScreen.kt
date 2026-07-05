package com.jhellerstedt.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
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
import com.jhellerstedt.zulip.ZulipServices

class ZulipHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ZulipHomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<ZulipHomeViewModel>
        get() = ZulipHomeViewModel::class.java

    override fun createViewModel(): ZulipHomeViewModel =
        ZulipHomeViewModel(ZulipServices.globalStore(lightContext))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        center = LightTopBarCenter.Text(state.realmTitle),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    if (state.isLoading) {
                        LightText(
                            text = "Syncing…",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                    }

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        HomeActionRow(
                            label = "Streams",
                            detail = "${state.streamCount} streams",
                            onClick = { navigateTo(screenFactory = { StreamListScreen(it) }) },
                        )
                        HomeActionRow(
                            label = "Direct messages",
                            detail = "${state.directMessageCount} conversations",
                            onClick = { navigateTo(screenFactory = { DirectMessageListScreen(it) }) },
                        )
                    }

                    LightBottomBar(
                        items = listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.SETTINGS,
                                onClick = { navigateTo(screenFactory = { SettingsScreen(it) }) },
                                contentDescription = "Settings",
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

@Composable
private fun HomeActionRow(
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Heading)
        LightText(text = detail, variant = LightTextVariant.Detail, lighten = true)
    }
}
