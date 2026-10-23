package com.jhellerstedt.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.jhellerstedt.zulip.ZulipServices

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel =
        SettingsViewModel(ZulipServices.globalStore(lightContext))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LaunchedEffect(state.navigateChooseAccount) {
            if (state.navigateChooseAccount) {
                viewModel.consumeNavigateChooseAccount()
                navigateTo(screenFactory = { ChooseAccountScreen(it, false) })
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Settings"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    state.activeAccount?.let { account ->
                        Column(modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp())) {
                            LightText(text = "Active realm", variant = LightTextVariant.Detail, lighten = true)
                            LightText(
                                text = account.realmName.ifBlank { account.realmUrl },
                                variant = LightTextVariant.Heading,
                            )
                            LightText(text = account.email, variant = LightTextVariant.Copy)
                        }
                    }

                    SettingActionRow(label = "Data leaving this phone", onClick = {
                        navigateTo(screenFactory = { DataDisclosureScreen(it) })
                    })
                    SettingActionRow(label = "Switch realm", onClick = viewModel::openChooseAccount)
                    SettingActionRow(label = "Add account", onClick = {
                        navigateTo(screenFactory = { LoginScreen(it, LoginMode.ServerUrl) })
                    })
                    SettingActionRow(label = "Remove active account", onClick = viewModel::removeActiveAccount)
                }

                LightBottomBar(items = listOf(null, null, null))
            }
        }
    }
}

@Composable
private fun SettingActionRow(label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Heading)
    }
}
