package com.jhellerstedt.zulip.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.jhellerstedt.zulip.util.MessagePart
import com.jhellerstedt.zulip.util.ReactionEmoji
import com.jhellerstedt.zulip.util.formatTimestamp
import com.jhellerstedt.zulip.util.messageParts
import com.jhellerstedt.zulip.util.reactionSummary
import com.jhellerstedt.zulip.util.reactorId
import com.jhellerstedt.zulip.util.resolveRealmContentUrl
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        var expandedPhoto by remember { mutableStateOf<ImageBitmap?>(null) }
        var contextMessageId by remember { mutableStateOf<Int?>(null) }

        ReturnHomeOnShow()
        if (!returningToMenu()) {
            LaunchedEffect(narrow) {
                viewModel.loadMessages()
            }
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

                    val statusText = when {
                        state.connectionState == ConnectionState.OFFLINE -> "Offline — showing saved data"
                        state.isRefreshing && state.messages.isNotEmpty() -> "Refreshing…"
                        state.isRefreshing -> "Loading messages…"
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
                        scrollState = scrollState,
                    ) {
                        state.messages.forEach { message ->
                            Column(
                                modifier = Modifier
                                    .padding(bottom = 1f.gridUnitsAsDp())
                                    .pointerInput(message.id) {
                                        detectTapGestures(
                                            onDoubleTap = { viewModel.toggleLike(message.id) },
                                            onLongPress = { contextMessageId = message.id },
                                        )
                                    },
                            ) {
                                val sender = state.authorNamesByMessageId[message.id] ?: "Unknown"
                                LightText(
                                    text = "$sender · ${formatTimestamp(message.timestamp)}",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                                messageParts(message.content).forEach { part ->
                                    when (part) {
                                        is MessagePart.Text -> LightText(
                                            text = part.text,
                                            variant = LightTextVariant.Copy,
                                        )
                                        is MessagePart.FileLink -> LightText(
                                            text = "File: ${part.name}",
                                            variant = LightTextVariant.Copy,
                                        )
                                        is MessagePart.Photo -> ZulipPhoto(
                                            name = part.name,
                                            url = part.url,
                                            onOpen = { expandedPhoto = it },
                                        )
                                    }
                                }
                                val reactions = reactionSummary(message, state.ownUserId, state.userNames)
                                if (reactions.isNotEmpty()) {
                                    LightText(
                                        text = reactions,
                                        variant = LightTextVariant.Superfine,
                                        modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                                    )
                                }
                                if (state.reactionErrorMessageId == message.id) {
                                    LightText(
                                        text = "reaction failed",
                                        variant = LightTextVariant.Superfine,
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
                            homeBarButton(ZulipServices.globalStore(lightContext)),
                            LightBarButton.LightIcon(
                                icon = LightIcons.COMPOSE_MESSAGE,
                                onClick = { navigateTo(screenFactory = { ComposeScreen(it, narrow) }) },
                                contentDescription = "Compose",
                            ),
                        ),
                    )
                }

                state.errorMessage?.let { message ->
                    com.thelightphone.sdk.ui.LightFullscreenModal(
                        message = message,
                        onClose = viewModel::dismissError,
                    )
                }

                expandedPhoto?.let { bitmap ->
                    ExpandedPhoto(bitmap = bitmap, onClose = { expandedPhoto = null })
                }

                val contextMessage = state.messages.firstOrNull { it.id == contextMessageId }
                ReactionContextPanel(
                    visible = contextMessage != null,
                    hasOwnReaction = contextMessage?.reactions?.any { it.reactorId() == state.ownUserId } == true,
                    onLike = {
                        contextMessage?.let { viewModel.setReaction(it.id, ReactionEmoji.like) }
                        contextMessageId = null
                    },
                    onReact = { emoji ->
                        contextMessage?.let { viewModel.setReaction(it.id, emoji) }
                        contextMessageId = null
                    },
                    onRemove = {
                        contextMessage?.let { viewModel.removeReaction(it.id) }
                        contextMessageId = null
                    },
                    onDismiss = { contextMessageId = null },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    @Composable
    private fun ExpandedPhoto(bitmap: ImageBitmap, onClose: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap = bitmap,
                contentDescription = "Photo",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .lightClickable(onClick = onClose),
                contentScale = ContentScale.Fit,
            )
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = onClose,
                        contentDescription = "Back",
                    ),
                    null,
                    null,
                ),
            )
        }
    }

    @Composable
    private fun ZulipPhoto(name: String, url: String, onOpen: (ImageBitmap) -> Unit) {
        var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(url) { mutableStateOf(false) }
        LaunchedEffect(url) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val client = ZulipServices.clientForActiveAccount(lightContext) ?: return@runCatching null
                    try {
                        val bytes = client.getBytes(resolveRealmContentUrl(client.realmUrl, url))
                            ?: return@runCatching null
                        decodeMessageImage(bytes)
                    } finally {
                        client.close()
                    }
                }.getOrNull()
            }
            if (loaded == null) failed = true else bitmap = loaded
        }
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8f.gridUnitsAsDp())
                    .lightClickable { onOpen(image) },
                contentScale = ContentScale.Fit,
            )
        } else {
            LightText(
                text = if (failed) "Photo: $name" else "Loading photo…",
                variant = LightTextVariant.Detail,
            )
        }
    }
}

private fun decodeMessageImage(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, 1080)
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return bitmap.asImageBitmap()
}

private fun imageSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    while (width / sample > maxEdge || height / sample > maxEdge) sample *= 2
    return sample
}
