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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightFullscreenModal
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
import com.jhellerstedt.zulip.NotificationPermission
import com.jhellerstedt.zulip.ZulipServices
import com.jhellerstedt.zulip.launchNotificationPermissionPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel =
        SettingsViewModel(
            ZulipServices.globalStore(lightContext),
            ZulipServices.preferences(lightContext),
        )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val notificationPermission = rememberPermissionRequestLauncher(
            "android.permission.POST_NOTIFICATIONS",
        )
        val scope = rememberCoroutineScope()
        var permissionPokes by remember { mutableIntStateOf(0) }
        var permissionNote by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(permissionPokes) {
            if (permissionPokes == 0) return@LaunchedEffect
            repeat(5) {
                delay(500)
                ZulipServices.pokeNotificationAsterisk()
            }
        }
        ReturnHomeOnShow()

        LaunchedEffect(state.navigateChooseAccount) {
            if (state.navigateChooseAccount) {
                viewModel.consumeNavigateChooseAccount()
                navigateTo(screenFactory = { ChooseAccountScreen(it, false) }) {
                    goBack()
                }
            }
        }

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
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

                    SettingActionRow(
                        label = "Notification asterisk",
                        detail = if (state.notificationAsterisk) "On" else "Off",
                        onClick = {
                            val enable = !state.notificationAsterisk
                            viewModel.setNotificationAsterisk(enable)
                            if (!enable) {
                                ZulipServices.pokeNotificationAsterisk()
                                return@SettingActionRow
                            }
                            scope.launch {
                                val permission = ZulipServices.notificationPermission()
                                if (launchNotificationPermissionPrompt(permission)) {
                                    notificationPermission?.launch()
                                    permissionPokes += 1
                                } else {
                                    ZulipServices.pokeNotificationAsterisk()
                                    if (permission == NotificationPermission.Unavailable) {
                                        permissionNote =
                                            "Unread marks inside Zulip stay on. This phone won't put an asterisk beside Zulip."
                                    }
                                }
                            }
                        },
                    )
                    SettingActionRow(label = "Data leaving this phone", onClick = {
                        navigateTo(screenFactory = { DataDisclosureScreen(it) })
                    })
                    SettingActionRow(label = "Switch realm", onClick = viewModel::openChooseAccount)
                    SettingActionRow(label = "Add account", onClick = {
                        navigateTo(screenFactory = { LoginScreen(it, LoginMode.ServerUrl) })
                    })
                    SettingActionRow(label = "Remove active account", onClick = viewModel::removeActiveAccount)
                }

                LightBottomBar(
                    items = listOf(
                        null,
                        homeBarButton(ZulipServices.globalStore(lightContext)),
                        null,
                    ),
                )
            }
            permissionNote?.let { message ->
                LightFullscreenModal(
                    message = message,
                    onClose = { permissionNote = null },
                )
            }
            }
        }
    }
}

@Composable
private fun SettingActionRow(label: String, onClick: () -> Unit, detail: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Heading)
        detail?.let {
            LightText(text = it, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}
