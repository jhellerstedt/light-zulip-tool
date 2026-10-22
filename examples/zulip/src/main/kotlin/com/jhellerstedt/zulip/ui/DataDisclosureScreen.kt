package com.jhellerstedt.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jhellerstedt.zulip.ZulipServices
import com.jhellerstedt.zulip.store.GlobalStore
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

class DataDisclosureViewModel(
    globalStore: GlobalStore,
) : ZulipViewModel<Unit>(globalStore)

class DataDisclosureScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, DataDisclosureViewModel>(sealedActivity) {

    override val viewModelClass: Class<DataDisclosureViewModel>
        get() = DataDisclosureViewModel::class.java

    override fun createViewModel(): DataDisclosureViewModel =
        DataDisclosureViewModel(ZulipServices.globalStore(lightContext))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

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
                    center = LightTopBarCenter.Text("Data"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    DisclosureParagraph(
                        "This tool sends data only to the Zulip server URL you enter. That address must use HTTPS.",
                    )
                    DisclosureParagraph(
                        "When you sign in, your email and password are sent once so the server can return an API key. After that, the API key is sent with each request. Messages you send, and the list of people in the realm, also go to that server.",
                    )
                    DisclosureParagraph(
                        "On this phone, the API key is stored encrypted in the Android keystore. Recent messages and the realm directory are kept in a local cache so the tool can open offline.",
                    )
                    DisclosureParagraph("There is no analytics and no push notification.")
                }

                LightBottomBar(items = listOf(null, null, null))
            }
        }
    }
}

@Composable
private fun DisclosureParagraph(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )
}
