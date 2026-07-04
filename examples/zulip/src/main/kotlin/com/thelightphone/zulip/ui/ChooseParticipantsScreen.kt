package com.thelightphone.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import com.thelightphone.lp3Keyboard.ui.CapsLockedLayout
import com.thelightphone.lp3Keyboard.ui.DefaultLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.EmojiLayout
import com.thelightphone.lp3Keyboard.ui.ExtendedCharKeyboard
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.LowerCaseLayout
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.Lp3RepeatableKeyboardCallback
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
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.zulip.ZulipServices
import com.thelightphone.zulip.store.Narrow
import kotlinx.coroutines.flow.StateFlow

private const val UNDERLINE_THICKNESS_PX = 3f
private const val UNDERLINE_WIDTH_FRACTION = 0.8f

class ChooseParticipantsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ChooseParticipantsViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChooseParticipantsViewModel>
        get() = ChooseParticipantsViewModel::class.java

    override fun createViewModel(): ChooseParticipantsViewModel =
        ChooseParticipantsViewModel(ZulipServices.globalStore(lightContext))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val queryState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val keyboardCallback = remember(queryState) { ParticipantKeyboardCallback(queryState) }
        val keyboardViewModel: Lp3KeyboardViewModel = remember(keyboardCallback, keyboardOptionsFlow) {
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
        val usersById = remember(state.users) { state.users.associateBy { it.userId } }
        val query = queryState.text.toString()
        val suggestions = filterParticipantSuggestions(
            users = state.users,
            query = query,
            selectedUserIds = state.selectedUserIds.toSet(),
        )
        val selectedSummary = buildParticipantSummary(state.selectedUserIds, usersById)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Choose participants"),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    if (state.selectedUserIds.isNotEmpty()) {
                        LightText(text = "To", variant = LightTextVariant.Detail)
                        LightText(
                            text = selectedSummary,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                        )
                        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    }

                    LightText(text = "Search", variant = LightTextVariant.Detail)
                    LightText(
                        text = if (query.isBlank()) "Type a name or email" else query,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                    )
                    Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth(UNDERLINE_WIDTH_FRACTION)
                            .height(UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                            .background(LightThemeTokens.colors.content),
                    )
                }

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    val placeholder = participantSuggestionsPlaceholder(
                        users = state.users,
                        query = query,
                        selectedUserIds = state.selectedUserIds,
                    )
                    when {
                        placeholder != null -> {
                            LightText(
                                text = placeholder,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                        }
                        else -> {
                            suggestions.forEach { user ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            viewModel.addParticipant(user.userId)
                                            clearQuery(queryState)
                                        }
                                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                                ) {
                                    LightText(text = user.fullName, variant = LightTextVariant.Heading)
                                    if (user.email.isNotBlank()) {
                                        LightText(
                                            text = user.email,
                                            variant = LightTextVariant.Detail,
                                            modifier = Modifier.padding(top = 0.125f.gridUnitsAsDp()),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        if (state.selectedUserIds.isEmpty()) {
                            null
                        } else {
                            LightBarButton.Text(
                                text = "CLEAR",
                                onClick = viewModel::clearParticipants,
                            )
                        },
                        if (state.selectedUserIds.isEmpty()) {
                            null
                        } else {
                            LightBarButton.Text(
                                text = "NEXT",
                                onClick = {
                                    navigateTo(
                                        screenFactory = {
                                            ComposeScreen(it, Narrow.Dm(state.selectedUserIds))
                                        },
                                    )
                                },
                            )
                        },
                    ),
                )
            }
        }
    }
}

private fun clearQuery(state: TextFieldState) {
    state.edit {
        delete(0, length)
        selection = TextRange.Zero
    }
}

private class ParticipantKeyboardCallback(
    private val state: TextFieldState,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onKeyReleased(code: Int) {
        insertAtCursor(buildString { appendCodePoint(code) })
    }

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> deleteBeforeCursor()
            SpecialKey.Return -> insertAtCursor("\n")
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            state.edit {
                if (selection.min == 0) return@edit
                delete(0, selection.min)
                selection = TextRange.Zero
            }
        }
    }

    override fun onKeyRepeated(code: Int) {
        insertAtCursor(buildString { appendCodePoint(code) })
    }

    override fun onSpecialKeyRepeated(key: SpecialKey) {
        if (key == SpecialKey.Space) insertAtCursor(" ")
    }

    private fun insertAtCursor(text: String) {
        state.edit {
            val start = selection.min
            val end = selection.max
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor() {
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = end - 1
            delete(start, end)
            selection = TextRange(start)
        }
    }
}
