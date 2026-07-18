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
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.Narrow

class TopicListScreen(
    sealedActivity: SealedLightActivity,
    private val streamId: Int,
    private val streamName: String,
) : LightScreen<Unit, TopicListViewModel>(sealedActivity) {

    override val viewModelClass: Class<TopicListViewModel>
        get() = TopicListViewModel::class.java

    override fun createViewModel(): TopicListViewModel =
        TopicListViewModel(ZulipServices.globalStore(lightContext), streamId, streamName)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LaunchedEffect(streamId) {
            viewModel.loadTopics()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        center = LightTopBarCenter.Text(state.title),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    val statusText = when {
                        state.connectionState == ConnectionState.OFFLINE -> "Offline — showing saved data"
                        state.isRefreshing && state.topics.isNotEmpty() -> "Refreshing…"
                        state.isRefreshing -> "Loading topics…"
                        else -> null
                    }
                    statusText?.let { text ->
                        LightText(
                            text = text,
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
                        if (state.topics.isEmpty() && !state.isRefreshing) {
                            LightText(
                                text = "No topics",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                        } else {
                            state.topics.forEach { topic ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            navigateTo(
                                                screenFactory = {
                                                    MessageListScreen(it, Narrow.Topic(streamId, topic.name))
                                                },
                                            )
                                        }
                                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                                ) {
                                    LightText(text = topic.name, variant = LightTextVariant.Heading)
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
