package com.jhellerstedt.zulip.ui

import android.Manifest
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.layout.EnShared
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.LightMedia
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.jhellerstedt.zulip.ZulipServices
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.store.Narrow
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class ComposeScreen(
    sealedActivity: SealedLightActivity,
    private val narrow: Narrow,
) : LightScreen<Unit, ComposeViewModel>(sealedActivity) {

    private var resumeCount by mutableIntStateOf(0)
    private var waitingForPhotoPermission = false

    override fun willShow() {
        resumeCount++
    }

    override val viewModelClass: Class<ComposeViewModel>
        get() = ComposeViewModel::class.java

    override fun createViewModel(): ComposeViewModel =
        ComposeViewModel(ZulipServices.globalStore(lightContext), narrow)

    override fun onScreenDestroy() {
        deleteDraftFiles(viewModel.uiState.value.attachments)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val textFieldState = rememberTextFieldState("")
        var mentionTokens by remember { mutableStateOf(emptyList<ComposeMentionToken>()) }
        val editorText = textFieldState.text.toString()
        val activeMention = remember(editorText, textFieldState.selection) {
            findActiveMentionQuery(editorText, textFieldState.selection)
        }
        val mentionSuggestions = remember(state.mentionableUsers, activeMention) {
            activeMention?.let { query ->
                filterMentionSuggestions(
                    users = state.mentionableUsers,
                    query = query.query,
                    limit = 2,
                )
            }.orEmpty()
        }
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val scope = rememberCoroutineScope()
        var albumOpen by remember { mutableStateOf(false) }
        var albumLoading by remember { mutableStateOf(false) }
        var photos by remember { mutableStateOf<List<LightMedia.Item.Image>>(emptyList()) }
        var attachingIds by remember { mutableStateOf(emptySet<Long>()) }
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.READ_MEDIA_IMAGES)
        val draftDirectory = remember { File(lightContext.filesDir, "zulip-compose-drafts") }

        suspend fun showPhotoLibrary() {
            val granted = checkPermission(Manifest.permission.READ_MEDIA_IMAGES).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
                .getOrDefault(false)
            if (!granted) {
                waitingForPhotoPermission = true
                permissionLauncher?.launch()
                return
            }
            waitingForPhotoPermission = false
            albumOpen = true
            albumLoading = true
            photos = lightContext.media.loadImages(limit = 100).items
            albumLoading = false
        }

        fun closeAlbum() {
            albumOpen = false
        }

        fun togglePhoto(image: LightMedia.Item.Image) {
            val existing = state.attachments.filter { it.mediaId == image.id }
            if (existing.isNotEmpty()) {
                deleteDraftFiles(existing)
                viewModel.replaceAttachments(state.attachments.filter { it.mediaId != image.id })
                return
            }
            if (image.id in attachingIds) return
            attachingIds = attachingIds + image.id
            scope.launch {
                try {
                    val bytes = lightContext.media.readItem(image) { input -> input.readBytes() }
                    if (bytes == null) {
                        viewModel.reportError("Could not read ${image.name}")
                        return@launch
                    }
                    val draft = writeComposeDraft(
                        directory = draftDirectory,
                        displayName = image.name,
                        bytes = bytes,
                        sourceIndex = state.attachments.size,
                        mediaId = image.id,
                    )
                    viewModel.replaceAttachments(state.attachments + draft)
                } finally {
                    attachingIds = attachingIds - image.id
                }
            }
        }

        LaunchedEffect(resumeCount) {
            if (waitingForPhotoPermission) showPhotoLibrary()
        }

        LaunchedEffect(state.sent) {
            if (state.sent) {
                viewModel.consumeSent()
                goBack()
            }
        }

        fun applyMentionSelection(user: ZulipUser) {
            val query = activeMention ?: return
            val accountId = state.mentionTokenAccountId ?: return
            val result = insertMention(
                text = editorText,
                activeQuery = query,
                accountId = accountId,
                user = user,
                tokens = mentionTokens,
            )
            mentionTokens = result.tokens
            textFieldState.edit {
                replace(0, length, result.updatedText)
                selection = result.selection
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .fillMaxWidth(),
                ) {
                    if (albumOpen) {
                        LightTopBar(
                            center = LightTopBarCenter.Text("Album"),
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                        AlbumPicker(
                            photos = photos,
                            loading = albumLoading,
                            selectedIds = state.attachments.mapNotNull { it.mediaId }.toSet(),
                            onToggle = ::togglePhoto,
                            decodeThumbnail = { image ->
                                val bytes = lightContext.media.readItem(image) { input -> input.readBytes() }
                                if (bytes == null) null else decodeAlbumThumbnail(bytes)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        LightBottomBar(
                            items = listOf(
                                LightBarButton.LightIcon(
                                    icon = LightIcons.BACK,
                                    onClick = ::closeAlbum,
                                    contentDescription = "Back",
                                ),
                                null,
                                LightBarButton.Text(
                                    text = "DONE",
                                    onClick = ::closeAlbum,
                                ),
                            ),
                        )
                    } else {
                    LightTopBar(
                        center = LightTopBarCenter.Text(state.title),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    if (state.attachments.isNotEmpty()) {
                        LightText(
                            text = if (state.isSending) {
                                "Queueing ${state.attachments.size} photos..."
                            } else {
                                buildAttachmentSummary(state.attachments.map { it.displayName })
                            },
                            variant = LightTextVariant.Detail,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2f.gridUnitsAsDp()),
                        )
                    }

                    ComposeMessageEditor(
                        state = textFieldState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )

                    if (activeMention != null) {
                        MentionSuggestions(
                            query = activeMention.query,
                            suggestions = mentionSuggestions,
                            onSuggestionSelected = ::applyMentionSelection,
                        )
                    }

                    ComposeKeyboard(
                        editorKey = state.editorSession,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        textFieldState = textFieldState,
                        onEdit = { start, endExclusive, insertedText ->
                            mentionTokens = applyTextEditToMentionTokens(
                                tokens = mentionTokens,
                                start = start,
                                endExclusive = endExclusive,
                                insertedText = insertedText,
                            )
                        },
                    )

                    LightBottomBar(
                        items = listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = if (state.isSending) null else { { goBack() } },
                                contentDescription = "Back",
                            ),
                            LightBarButton.LightIcon(
                                icon = LightIcons.MEDIA,
                                onClick = if (state.isSending) {
                                    null
                                } else {
                                    { scope.launch { showPhotoLibrary() } }
                                },
                                contentDescription = "Add photo",
                            ),
                            LightBarButton.Text(
                                text = if (state.isSending) "QUEUEING" else "SEND",
                                onClick = if (state.isSending) {
                                    null
                                } else {
                                    {
                                        viewModel.sendMessage(
                                            formatMessageWithMentions(
                                                text = textFieldState.text.toString(),
                                                tokens = mentionTokens,
                                                currentAccountId = state.mentionTokenAccountId,
                                            ),
                                        )
                                    }
                                },
                            ),
                        ),
                    )
                    }
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
private fun AlbumPicker(
    photos: List<LightMedia.Item.Image>,
    loading: Boolean,
    selectedIds: Set<Long>,
    onToggle: (LightMedia.Item.Image) -> Unit,
    decodeThumbnail: suspend (LightMedia.Item.Image) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    if (loading) {
        LightText(
            text = "Loading photos…",
            variant = LightTextVariant.Copy,
            modifier = modifier.padding(horizontal = 2f.gridUnitsAsDp()),
        )
        return
    }
    if (photos.isEmpty()) {
        LightText(
            text = "No photos",
            variant = LightTextVariant.Copy,
            modifier = modifier.padding(horizontal = 2f.gridUnitsAsDp()),
        )
        return
    }
    val rows = photos.chunked(2)
    LightLazyScrollView(
        modifier = modifier,
        uniformItemHeightGridUnits = 8f,
    ) {
        items(rows, key = { row -> row.first().id }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8f.gridUnitsAsDp()),
            ) {
                row.forEach { photo ->
                    AlbumTile(
                        photo = photo,
                        selected = photo.id in selectedIds,
                        onClick = { onToggle(photo) },
                        decodeThumbnail = decodeThumbnail,
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.AlbumTile(
    photo: LightMedia.Item.Image,
    selected: Boolean,
    onClick: () -> Unit,
    decodeThumbnail: suspend (LightMedia.Item.Image) -> ImageBitmap?,
) {
    var bitmap by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(photo.id) { mutableStateOf(false) }
    LaunchedEffect(photo.id) {
        val decoded = decodeThumbnail(photo)
        if (decoded == null) failed = true else bitmap = decoded
    }
    val border = if (selected) {
        Modifier.border(3.dp, LightThemeTokens.colors.content)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(0.25f.gridUnitsAsDp())
            .then(border)
            .lightClickable(onClick = onClick),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = photo.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (failed) {
            LightText(
                text = photo.name,
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(0.5f.gridUnitsAsDp()),
            )
        }
        if (selected) {
            LightText(
                text = "Selected",
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(0.5f.gridUnitsAsDp()),
            )
        }
    }
}

private fun decodeAlbumThumbnail(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = albumSampleSize(bounds.outWidth, bounds.outHeight, 480)
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return bitmap.asImageBitmap()
}

private fun albumSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    while (width / sample > maxEdge || height / sample > maxEdge) sample *= 2
    return sample
}

@Composable
private fun MentionSuggestions(
    query: String,
    suggestions: List<ZulipUser>,
    onSuggestionSelected: (ZulipUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp())
            .padding(bottom = 0.5f.gridUnitsAsDp()),
    ) {
        if (query.isNotBlank() && suggestions.isEmpty()) {
            LightText(
                text = "No matches",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
            )
        } else {
            suggestions.forEach { user ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { onSuggestionSelected(user) }
                        .padding(vertical = 0.5f.gridUnitsAsDp()),
                ) {
                    LightText(text = user.fullName, variant = LightTextVariant.Heading)
                    if (user.email.isNotBlank()) {
                        LightText(
                            text = user.email,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposeMessageEditor(
    state: TextFieldState,
    modifier: Modifier = Modifier,
) {
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(
        modifier = modifier
            .padding(horizontal = 2f.gridUnitsAsDp())
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    textLayout?.let { layout ->
                        state.edit {
                            selection = TextRange(layout.getOffsetForPosition(down.position))
                        }
                    }
                    drag(down.id) { change ->
                        textLayout?.let { layout ->
                            state.edit {
                                selection = TextRange(layout.getOffsetForPosition(change.position))
                            }
                        }
                        change.consume()
                    }
                }
            },
    ) {
        BasicText(
            text = state.text.toString(),
            style = composeInputTextStyle(),
            onTextLayout = { textLayout = it },
            modifier = Modifier.fillMaxWidth(),
        )
        textLayout?.let { layout ->
            val cursorPos = state.selection.min.coerceIn(0, layout.layoutInput.text.length)
            val rect = layout.getCursorRect(cursorPos)
            Box(
                modifier = Modifier
                    .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                    .width(2.dp)
                    .height(with(androidx.compose.ui.platform.LocalDensity.current) { rect.height.toDp() })
                    .background(LightThemeTokens.colors.content),
            )
        }
    }
}

@Composable
private fun ComposeKeyboard(
    editorKey: Int,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    textFieldState: TextFieldState,
    onEdit: (Int, Int, String) -> Unit,
) {
    val keyboardCallback = remember(textFieldState, onEdit) {
        ComposeKeyboardCallback(textFieldState, onEdit)
    }
    val keyboardViewModel: Lp3KeyboardViewModel<Unit> = remember(editorKey, keyboardCallback, keyboardOptionsFlow) {
        EnQwertyLp3KeyboardViewModel(
            keyboardCallback,
            keyboardOptionsFlow = keyboardOptionsFlow,
            optionsForLayout = { layout ->
                LayoutOptions(displayCloseButton = layout is EnShared.ExtendedCharKeyboard || layout == EnShared.EmojiLayout)
            },
        )
    }
    LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)
}

@Composable
private fun composeInputTextStyle(): TextStyle {
    val base = LightThemeTokens.typography.copy
    return base.copy(
        color = LightThemeTokens.colors.content,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        fontSize = base.fontSize.value.designVerticalPxToSp(),
        lineHeight = base.lineHeight.value.designVerticalPxToSp(),
    )
}

private class ComposeKeyboardCallback(
    private val state: TextFieldState,
    private val onEdit: (Int, Int, String) -> Unit,
) : Lp3RepeatableKeyboardCallback {
    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onKeyReleased(code: Int) {
        insertCodePoint(code)
    }

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> {
                val before = state.text.subSequence(0, state.selection.min)
                deleteBeforeCursor(surrogateAwareDeleteCount(before, 1))
            }
            SpecialKey.Return -> insertAtCursor("\n")
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            val before = state.text.subSequence(0, state.selection.min)
            deleteBeforeCursor(deleteWordCount(before))
        }
    }

    override fun onKeyRepeated(code: Int) {
        insertCodePoint(code)
    }

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onSubmitWord(word: CharSequence) = Unit

    private fun insertCodePoint(code: Int) {
        insertAtCursor(buildString { appendCodePoint(code) })
    }

    private fun insertAtCursor(text: String) {
        val start = state.selection.min
        val end = state.selection.max
        onEdit(start, end, text)
        state.edit {
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor(count: Int) {
        if (count <= 0) return
        val selectionStart = state.selection.min
        val selectionEnd = state.selection.max
        val end = selectionEnd
        val start = if (selectionStart != selectionEnd) {
            selectionStart
        } else {
            if (end == 0) return
            (end - count).coerceAtLeast(0)
        }
        onEdit(start, end, "")
        state.edit {
            delete(start, end)
            selection = TextRange(start)
        }
    }
}

private fun surrogateAwareDeleteCount(value: CharSequence, defaultCount: Int): Int {
    if (value.isEmpty()) return 0
    val last = value[value.length - 1]
    return if (Character.isLowSurrogate(last)) 2 else defaultCount
}

private fun deleteWordCount(value: CharSequence): Int {
    val trimmed = value.trimEnd()
    val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
    return value.length - if (lastSpace >= 0) lastSpace + 1 else 0
}
