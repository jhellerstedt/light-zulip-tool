package com.thelightphone.zulip.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.thelightphone.lp3Keyboard.ui.CapsLockedLayout
import com.thelightphone.lp3Keyboard.ui.DefaultLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.EmojiLayout
import com.thelightphone.lp3Keyboard.ui.ExtendedCharKeyboard
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.LowerCaseLayout
import com.thelightphone.lp3Keyboard.ui.Lp3RepeatableKeyboardCallback
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.NumberLayout
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.SymbolsLayout
import com.thelightphone.lp3Keyboard.ui.UpperCaseLayout
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.zulip.ZulipServices
import com.thelightphone.zulip.api.models.ZulipUser
import com.thelightphone.zulip.store.Narrow
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow

class ComposeScreen(
    sealedActivity: SealedLightActivity,
    private val narrow: Narrow,
) : LightScreen<Unit, ComposeViewModel>(sealedActivity) {

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
                filterMentionSuggestions(users = state.mentionableUsers, query = query.query)
            }.orEmpty()
        }
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state.sent) {
            if (state.sent) {
                viewModel.consumeSent()
                goBack()
            }
        }

        val currentAttachments = rememberUpdatedState(state.attachments)
        val photoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            copyPickedPhotosToDrafts(
                lightContext = lightContext,
                uris = uris,
                filesDir = lightContext.filesDir,
            ).onSuccess { drafts ->
                deleteDraftFiles(currentAttachments.value)
                viewModel.replaceAttachments(drafts)
            }.onFailure { error ->
                viewModel.showError(error.message ?: "Failed to load selected photos")
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
                    LightTopBar(
                        center = LightTopBarCenter.Text(state.title),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    if (state.attachments.isNotEmpty()) {
                        LightText(
                            text = if (state.isSending) {
                                "Uploading ${state.attachments.size} photos..."
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
                            LightBarButton.Text(
                                text = "PHOTO",
                                onClick = if (state.isSending) {
                                    null
                                } else {
                                    {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                                            ),
                                        )
                                    }
                                },
                            ),
                            LightBarButton.Text(
                                text = if (state.isSending) "SENDING" else "SEND",
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
    val keyboardViewModel: Lp3KeyboardViewModel = remember(editorKey, keyboardCallback, keyboardOptionsFlow) {
        DefaultLp3KeyboardViewModel(
            keyboardCallback,
            keyboardOptionsFlow = keyboardOptionsFlow,
            optionsForLayout = {
                val showCloseButton = when (it) {
                    EmojiLayout, is ExtendedCharKeyboard -> true
                    CapsLockedLayout, LowerCaseLayout, NumberLayout, SymbolsLayout, UpperCaseLayout -> false
                }
                LayoutOptions(showCloseButton)
            },
        )
    }
    LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)
}

@Composable
private fun composeInputTextStyle() = LightThemeTokens.typography.heading
    .copy(
        color = LightThemeTokens.colors.content,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
    )

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
