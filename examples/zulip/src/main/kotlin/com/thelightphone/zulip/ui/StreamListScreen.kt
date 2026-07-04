package com.thelightphone.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

class StreamListScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, StreamListViewModel>(sealedActivity) {

    override val viewModelClass: Class<StreamListViewModel>
        get() = StreamListViewModel::class.java

    override fun createViewModel(): StreamListViewModel =
        StreamListViewModel(ZulipServices.globalStore(lightContext))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val streams by viewModel.streams.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Streams"),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    streams.forEach { stream ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    navigateTo(
                                        screenFactory = {
                                            TopicListScreen(it, stream.streamId, stream.name)
                                        },
                                    )
                                }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        ) {
                            LightText(text = stream.name, variant = LightTextVariant.Heading)
                            if (stream.description.isNotBlank()) {
                                LightText(
                                    text = stream.description,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
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
                        null,
                    ),
                )
            }
        }
    }
}
