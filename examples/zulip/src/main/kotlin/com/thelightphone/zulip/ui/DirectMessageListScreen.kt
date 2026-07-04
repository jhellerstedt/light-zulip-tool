package com.thelightphone.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.thelightphone.zulip.ZulipServices

class DirectMessageListScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, DirectMessageListViewModel>(sealedActivity) {

    override val viewModelClass: Class<DirectMessageListViewModel>
        get() = DirectMessageListViewModel::class.java

    override fun createViewModel(): DirectMessageListViewModel =
        DirectMessageListViewModel(ZulipServices.globalStore(lightContext))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val rows by viewModel.rows.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Direct messages"),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    if (rows.isEmpty()) {
                        LightText(
                            text = "No direct messages",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                        )
                    } else {
                        rows.forEach { row ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable {
                                        navigateTo(screenFactory = { MessageListScreen(it, row.narrow) })
                                    }
                                    .padding(vertical = 0.75f.gridUnitsAsDp()),
                            ) {
                                LightText(text = row.title, variant = LightTextVariant.Heading)
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        null,
                        LightBarButton.LightIcon(
                            icon = LightIcons.COMPOSE_MESSAGE,
                            onClick = {
                                navigateTo(screenFactory = { ChooseParticipantsScreen(it) })
                            },
                            contentDescription = "Compose direct message",
                        ),
                    ),
                )
            }
        }
    }
}
