package com.thelightphone.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.thelightphone.zulip.ZulipServices
import com.thelightphone.zulip.store.Narrow
import com.thelightphone.zulip.util.formatTimestamp
import com.thelightphone.zulip.util.stripHtml

class MessageListScreen(
    sealedActivity: SealedLightActivity,
    private val narrow: Narrow,
) : LightScreen<Unit, MessageListViewModel>(sealedActivity) {

    override val viewModelClass: Class<MessageListViewModel>
        get() = MessageListViewModel::class.java

    override fun createViewModel(): MessageListViewModel =
        MessageListViewModel(ZulipServices.globalStore(lightContext), narrow)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val scrollState = rememberScrollState()

        LaunchedEffect(narrow) {
            viewModel.loadMessages()
        }

        LaunchedEffect(state.initialScrollIndex, scrollState.maxValue) {
            if (state.initialScrollIndex >= 0 && scrollState.maxValue > 0) {
                scrollState.scrollTo(scrollState.maxValue)
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
                        center = LightTopBarCenter.Text(state.title),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    if (state.isLoading) {
                        LightText(
                            text = "Loading messages…",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                    }

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                        scrollState = scrollState,
                    ) {
                        state.messages.forEach { message ->
                            Column(
                                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                            ) {
                                val sender = state.authorNamesByMessageId[message.id] ?: "Unknown"
                                LightText(
                                    text = "$sender · ${formatTimestamp(message.timestamp)}",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                                LightText(
                                    text = stripHtml(message.content),
                                    variant = LightTextVariant.Copy,
                                )
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
                            LightBarButton.Text(
                                text = "COMPOSE",
                                onClick = { navigateTo(screenFactory = { ComposeScreen(it, narrow) }) },
                            ),
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
