# Zulip Mentions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `@` user mention autocomplete to the Zulip compose screen, keep mentions readable while editing, and rewrite confirmed mentions to Zulip-safe `@**Full Name|id**` markup before sending.

**Architecture:** Keep all mention parsing, token tracking, and send-time rewriting in a new pure helper file so the risky logic is unit-testable outside Compose. Let `ComposeViewModel` surface the current realm-user directory to the screen, and keep `ComposeScreen` mostly as wiring: derive the active `@query`, render a simple suggestion list, keep token ranges in sync with text edits, and format the final outbound message before calling the existing send path.

**Tech Stack:** Kotlin, Jetpack Compose, existing LightOS SDK UI components, `LightEmbeddedLp3Keyboard`, `StateFlow`, `kotlin.test`, Gradle unit tests.

---

## File Structure

### Files to create

- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeMentionSupport.kt`
  - Pure mention models and helpers for active-query detection, suggestion filtering, token updates on edits, mention insertion, and send-time rewrite.
- `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeMentionSupportTest.kt`
  - Regression coverage for mention parsing, insertion, token shifting/invalidation, and Zulip markup rewriting.
- `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeViewModelTest.kt`
  - Verifies that compose UI state exposes and updates the mentionable realm-user list.

### Files to modify

- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeViewModel.kt`
  - Add `mentionableUsers` to `ComposeUiState`, observe the active store, and keep the title/send behavior intact.
- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeScreen.kt`
  - Derive active mention queries from editor text, render suggestions above the keyboard, update token ranges on every edit, insert readable mentions, and format confirmed mentions before send.

### Files to verify but probably not modify

- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/store/ZulipStore.kt`
  - Confirm `selectableUsersFlow` already contains the active realm-user directory used for mention autocomplete.
- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsViewModel.kt`
  - Reuse the active-store observation pattern if it helps keep `ComposeViewModel` small and consistent.

---

### Task 1: Build Pure Mention Helpers First

**Files:**
- Create: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeMentionSupport.kt`
- Test: `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeMentionSupportTest.kt`

- [ ] **Step 1: Write the failing helper tests**

Create `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeMentionSupportTest.kt` with:

```kotlin
package com.thelightphone.zulip

import androidx.compose.ui.text.TextRange
import com.thelightphone.zulip.api.models.ZulipUser
import com.thelightphone.zulip.ui.ComposeMentionToken
import com.thelightphone.zulip.ui.applyTextEditToMentionTokens
import com.thelightphone.zulip.ui.filterMentionSuggestions
import com.thelightphone.zulip.ui.findActiveMentionQuery
import com.thelightphone.zulip.ui.formatMessageWithMentions
import com.thelightphone.zulip.ui.insertMention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeMentionSupportTest {
    private val users = listOf(
        ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
        ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
        ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
    )

    @Test
    fun findsActiveMentionAtCursor() {
        val text = "Hello @ali"

        val query = findActiveMentionQuery(text, TextRange(text.length))

        assertEquals(6, query?.start)
        assertEquals(text.length, query?.endExclusive)
        assertEquals("ali", query?.query)
    }

    @Test
    fun ignoresAtSymbolsInsideWords() {
        val text = "email me at hi@example.com"

        val query = findActiveMentionQuery(text, TextRange(text.length))

        assertNull(query)
    }

    @Test
    fun blankMentionQueryShowsAlphabeticalSuggestions() {
        val suggestions = filterMentionSuggestions(users, query = "", limit = 2)

        assertEquals(listOf(1, 2), suggestions.map { it.userId })
    }

    @Test
    fun filtersMentionSuggestionsByNameAndEmail() {
        val byName = filterMentionSuggestions(users, query = "ali")
        val byEmail = filterMentionSuggestions(users, query = "bob@")

        assertEquals(listOf(1), byName.map { it.userId })
        assertEquals(listOf(2), byEmail.map { it.userId })
    }

    @Test
    fun insertMentionReplacesQueryAndAppendsTrailingSpace() {
        val text = "Hello @ali"
        val result = insertMention(
            text = text,
            activeQuery = checkNotNull(findActiveMentionQuery(text, TextRange(text.length))),
            user = users.first(),
            tokens = emptyList(),
        )

        assertEquals("Hello @Alice Adams ", result.updatedText)
        assertEquals(TextRange(result.updatedText.length), result.selection)
        assertEquals(
            listOf(ComposeMentionToken(userId = 1, displayText = "@Alice Adams", start = 6, endExclusive = 18)),
            result.tokens,
        )
    }

    @Test
    fun typingBeforeMentionShiftsTokenRange() {
        val shifted = applyTextEditToMentionTokens(
            tokens = listOf(
                ComposeMentionToken(userId = 1, displayText = "@Alice Adams", start = 6, endExclusive = 18),
            ),
            start = 0,
            endExclusive = 0,
            insertedText = ">> ",
        )

        assertEquals(
            listOf(ComposeMentionToken(userId = 1, displayText = "@Alice Adams", start = 9, endExclusive = 21)),
            shifted,
        )
    }

    @Test
    fun editingInsideMentionDropsTheToken() {
        val updated = applyTextEditToMentionTokens(
            tokens = listOf(
                ComposeMentionToken(userId = 1, displayText = "@Alice Adams", start = 3, endExclusive = 15),
            ),
            start = 7,
            endExclusive = 7,
            insertedText = "x",
        )

        assertEquals(emptyList(), updated)
    }

    @Test
    fun formatsMultipleConfirmedMentionsUsingIds() {
        val text = "Hi @Alice Adams and @Bob Brown"
        val formatted = formatMessageWithMentions(
            text = text,
            tokens = listOf(
                ComposeMentionToken(userId = 1, displayText = "@Alice Adams", start = 3, endExclusive = 15),
                ComposeMentionToken(userId = 2, displayText = "@Bob Brown", start = 20, endExclusive = 30),
            ),
        )

        assertEquals(
            "Hi @**Alice Adams|1** and @**Bob Brown|2**",
            formatted,
        )
    }

    @Test
    fun invalidTokenLeavesVisiblePlainTextUntouched() {
        val text = "Hi @Alicf Adams"
        val formatted = formatMessageWithMentions(
            text = text,
            tokens = listOf(
                ComposeMentionToken(userId = 1, displayText = "@Alice Adams", start = 3, endExclusive = 15),
            ),
        )

        assertEquals(text, formatted)
    }
}
```

- [ ] **Step 2: Run the helper tests and verify they fail**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest --tests com.thelightphone.zulip.ComposeMentionSupportTest
```

Expected: FAIL with unresolved references for `ComposeMentionToken`, `findActiveMentionQuery`, `filterMentionSuggestions`, `insertMention`, `applyTextEditToMentionTokens`, and `formatMessageWithMentions`.

- [ ] **Step 3: Implement the pure mention support file**

Create `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeMentionSupport.kt` with:

```kotlin
package com.thelightphone.zulip.ui

import androidx.compose.ui.text.TextRange
import com.thelightphone.zulip.api.models.ZulipUser

data class ComposeMentionToken(
    val userId: Int,
    val displayText: String,
    val start: Int,
    val endExclusive: Int,
)

data class ActiveMentionQuery(
    val start: Int,
    val endExclusive: Int,
    val query: String,
)

data class MentionInsertionResult(
    val updatedText: String,
    val selection: TextRange,
    val tokens: List<ComposeMentionToken>,
)

fun findActiveMentionQuery(
    text: CharSequence,
    selection: TextRange,
): ActiveMentionQuery? {
    if (selection.min != selection.max) return null
    val cursor = selection.min.coerceIn(0, text.length)
    var index = cursor - 1
    while (index >= 0 && !text[index].isWhitespace()) {
        if (text[index] == '@') {
            if (!hasValidMentionBoundary(text, index)) return null
            return ActiveMentionQuery(
                start = index,
                endExclusive = cursor,
                query = text.subSequence(index + 1, cursor).toString(),
            )
        }
        index--
    }
    return null
}

fun filterMentionSuggestions(
    users: List<ZulipUser>,
    query: String,
    limit: Int = 5,
): List<ZulipUser> {
    val trimmed = query.trim().lowercase()
    val ordered = users.sortedBy { it.fullName.lowercase() }
    if (trimmed.isBlank()) return ordered.take(limit)
    return ordered.filter { user ->
        user.fullName.lowercase().contains(trimmed) ||
            user.email.lowercase().contains(trimmed)
    }.take(limit)
}

fun applyTextEditToMentionTokens(
    tokens: List<ComposeMentionToken>,
    start: Int,
    endExclusive: Int,
    insertedText: String,
): List<ComposeMentionToken> {
    val delta = insertedText.length - (endExclusive - start)
    return tokens.mapNotNull { token ->
        when {
            endExclusive <= token.start -> token.copy(
                start = token.start + delta,
                endExclusive = token.endExclusive + delta,
            )
            start >= token.endExclusive -> token
            else -> null
        }
    }.sortedBy { it.start }
}

fun insertMention(
    text: String,
    activeQuery: ActiveMentionQuery,
    user: ZulipUser,
    tokens: List<ComposeMentionToken>,
): MentionInsertionResult {
    val mentionText = "@${user.fullName}"
    val replacement = "$mentionText "
    val updatedText = text.replaceRange(activeQuery.start, activeQuery.endExclusive, replacement)
    val updatedTokens = applyTextEditToMentionTokens(
        tokens = tokens,
        start = activeQuery.start,
        endExclusive = activeQuery.endExclusive,
        insertedText = replacement,
    ) + ComposeMentionToken(
        userId = user.userId,
        displayText = mentionText,
        start = activeQuery.start,
        endExclusive = activeQuery.start + mentionText.length,
    )
    return MentionInsertionResult(
        updatedText = updatedText,
        selection = TextRange(activeQuery.start + replacement.length),
        tokens = updatedTokens.sortedBy { it.start },
    )
}

fun formatMessageWithMentions(
    text: String,
    tokens: List<ComposeMentionToken>,
): String {
    var updated = text
    tokens.sortedByDescending { it.start }.forEach { token ->
        if (token.start < 0 || token.endExclusive > updated.length) return@forEach
        val visibleText = updated.substring(token.start, token.endExclusive)
        if (visibleText != token.displayText) return@forEach
        val fullName = token.displayText.removePrefix("@")
        updated = updated.replaceRange(
            token.start,
            token.endExclusive,
            "@**$fullName|${token.userId}**",
        )
    }
    return updated
}

private fun hasValidMentionBoundary(
    text: CharSequence,
    atIndex: Int,
): Boolean {
    if (atIndex == 0) return true
    val previous = text[atIndex - 1]
    return previous.isWhitespace() || previous in listOf('(', '[', '{', '"', '\'')
}
```

- [ ] **Step 4: Re-run the helper tests and verify they pass**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest --tests com.thelightphone.zulip.ComposeMentionSupportTest
```

Expected: PASS.

- [ ] **Step 5: Commit the helper-only change**

```bash
git add \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeMentionSupport.kt \
  examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeMentionSupportTest.kt
git commit -m "test: cover zulip compose mention helpers"
```

### Task 2: Surface Mentionable Users In Compose View State

**Files:**
- Modify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeViewModel.kt`
- Test: `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeViewModelTest.kt`

- [ ] **Step 1: Write the failing compose viewmodel tests**

Create `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeViewModelTest.kt` with:

```kotlin
package com.thelightphone.zulip

import com.thelightphone.zulip.api.ZulipClient
import com.thelightphone.zulip.api.models.RegisterQueueResponse
import com.thelightphone.zulip.api.models.ZulipUser
import com.thelightphone.zulip.data.StoredZulipAccount
import com.thelightphone.zulip.store.Narrow
import com.thelightphone.zulip.store.ZulipStore
import com.thelightphone.zulip.ui.ComposeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeViewModelTest {
    @Test
    fun uiStateSeedsMentionableUsersFromActiveStore() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(store)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(1)),
                observationScope = observerScope,
            )

            val state = withTimeout(1_000) {
                viewModel.uiState.first { it.mentionableUsers.map { user -> user.userId } == listOf(1, 2) }
            }

            assertEquals(listOf(1, 2), state.mentionableUsers.map { it.userId })
        } finally {
            observerScope.cancel()
            store.client.close()
        }
    }

    @Test
    fun uiStateUpdatesMentionableUsersWhenStoreChanges() = runBlocking {
        val firstStore = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val secondStore = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(firstStore)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(2)),
                observationScope = observerScope,
            )

            withTimeout(1_000) {
                viewModel.uiState.first { it.mentionableUsers.map { user -> user.userId } == listOf(1) }
            }

            activeStore.value = secondStore
            val updated = withTimeout(1_000) {
                viewModel.uiState
                    .drop(1)
                    .first { it.mentionableUsers.map { user -> user.userId } == listOf(2, 3) }
            }

            assertEquals(listOf(2, 3), updated.mentionableUsers.map { it.userId })
        } finally {
            observerScope.cancel()
            firstStore.client.close()
            secondStore.client.close()
        }
    }

    private fun storeWithUsers(
        selfUserId: Int,
        users: Array<out ZulipUser>,
    ): ZulipStore {
        val store = ZulipStore(
            account = StoredZulipAccount(
                id = 1L,
                realmUrl = "https://example.com",
                realmName = "Example",
                email = "self@example.com",
                userId = selfUserId,
                zulipFeatureLevel = 0,
            ),
            client = ZulipClient("https://example.com", "self@example.com", "api-key"),
        )
        store.applyRegisterResponse(
            RegisterQueueResponse(
                result = "success",
                realmUsers = users.toList(),
            ),
        )
        return store
    }
}
```

- [ ] **Step 2: Run the compose viewmodel tests and verify they fail**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest --tests com.thelightphone.zulip.ComposeViewModelTest
```

Expected: FAIL because `ComposeUiState` does not yet expose `mentionableUsers`, and `ComposeViewModel` does not yet support the testable constructor or store observation flow.

- [ ] **Step 3: Update `ComposeViewModel` to expose mentionable users**

Modify `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeViewModel.kt` like this:

```kotlin
data class ComposeUiState(
    val title: String = "Compose",
    val editorSession: Int = 0,
    val mentionableUsers: List<ZulipUser> = emptyList(),
    val attachments: List<ComposeAttachmentDraft> = emptyList(),
    val isSending: Boolean = false,
    val sent: Boolean = false,
    val errorMessage: String? = null,
)

class ComposeViewModel internal constructor(
    private val activeStore: StateFlow<ZulipStore?>,
    private val narrow: Narrow,
    private val observationScope: CoroutineScope? = null,
) : LightViewModel<Unit>() {
    constructor(globalStore: GlobalStore, narrow: Narrow) : this(globalStore.activeStore, narrow)

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    init {
        updateTitle(activeStore.value)
        (observationScope ?: viewModelScope).launch {
            activeStore.collect { store ->
                updateTitle(store)
            }
        }
        (observationScope ?: viewModelScope).launch {
            selectableUsersFlow(activeStore).collect { users ->
                _uiState.update { it.copy(mentionableUsers = users) }
            }
        }
    }

    private fun updateTitle(store: ZulipStore?) {
        val streamNames = store?.streamsList?.value?.associate { it.streamId to it.name }.orEmpty()
        val userNames = when (narrow) {
            is Narrow.Dm -> narrow.userIds.mapNotNull { id ->
                store?.userName(id)?.let { id to it }
            }.toMap()
            else -> emptyMap()
        }
        _uiState.update {
            it.copy(title = "Compose: ${narrow.displayTitle(streamNames, userNames)}")
        }
    }

    fun sendMessage(content: String) {
        val trimmed = content.trim()
        val attachments = _uiState.value.attachments.sortedBy { it.sourceIndex }
        if (trimmed.isBlank() && attachments.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Message cannot be empty") }
            return
        }
        val store = activeStore.value
        if (store == null) {
            _uiState.update { it.copy(errorMessage = "No active account") }
            return
        }
        _uiState.update { it.copy(isSending = true, errorMessage = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.send(
                    narrow = narrow,
                    content = trimmed,
                    attachments = attachments.map { attachment ->
                        PendingUploadAttachment(
                            displayName = attachment.displayName,
                            mimeType = attachment.mimeType,
                            file = File(attachment.filePath),
                        )
                    },
                )
            }.onSuccess {
                _uiState.update { it.copy(isSending = false, sent = true) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSending = false,
                        errorMessage = error.message ?: "Failed to send message",
                    )
                }
            }
        }
    }
}
```

Also add the missing imports at the top of the file:

```kotlin
import com.thelightphone.zulip.api.models.ZulipUser
import com.thelightphone.zulip.store.ZulipStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
```

- [ ] **Step 4: Re-run the compose viewmodel tests and verify they pass**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest --tests com.thelightphone.zulip.ComposeViewModelTest
```

Expected: PASS.

- [ ] **Step 5: Commit the compose viewmodel plumbing**

```bash
git add \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeViewModel.kt \
  examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeViewModelTest.kt
git commit -m "feat: surface zulip mentionable users in compose"
```

### Task 3: Wire Mention Autocomplete Into The Compose Screen

**Files:**
- Modify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeScreen.kt`
- Verify with: `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeMentionSupportTest.kt`
- Verify with: `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeViewModelTest.kt`

- [ ] **Step 1: Add local mention-token state and derive the active query**

In `ComposeScreen.kt`, extend `Content()` with local token state and derived suggestions:

```kotlin
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
        )
    }.orEmpty()
}
```

- [ ] **Step 2: Teach the keyboard callback to keep mention-token ranges synchronized**

Change `ComposeKeyboard` and `ComposeKeyboardCallback` to accept an edit callback:

```kotlin
@Composable
private fun ComposeKeyboard(
    editorKey: Int,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    textFieldState: TextFieldState,
    onEdit: (Int, Int, String) -> Unit,
) {
    val keyboardCallback = remember(textFieldState, onEdit) {
        ComposeKeyboardCallback(
            state = textFieldState,
            onEdit = onEdit,
        )
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

private class ComposeKeyboardCallback(
    private val state: TextFieldState,
    private val onEdit: (Int, Int, String) -> Unit,
) : Lp3RepeatableKeyboardCallback {
    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) insertAtCursor(" ")
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

    override fun onSpecialKeyRepeated(key: SpecialKey) {
        if (key == SpecialKey.Space) insertAtCursor(" ")
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
        val end = state.selection.min
        if (end == 0) return
        val start = (end - count).coerceAtLeast(0)
        onEdit(start, end, "")
        state.edit {
            delete(start, end)
            selection = TextRange(start)
        }
    }
}
```

Then call the keyboard with:

```kotlin
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
```

- [ ] **Step 3: Render the suggestion list and insert readable mentions on tap**

Add a local selection handler and a simple suggestion list composable in `ComposeScreen.kt`:

```kotlin
fun applyMentionSelection(user: ZulipUser) {
    val query = activeMention ?: return
    val result = insertMention(
        text = editorText,
        activeQuery = query,
        user = user,
        tokens = mentionTokens,
    )
    mentionTokens = result.tokens
    textFieldState.edit {
        replace(0, length, result.updatedText)
        selection = result.selection
    }
}
```

Render it between the message editor and the keyboard:

```kotlin
ComposeMessageEditor(
    state = textFieldState,
    modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
)

if (activeMention != null) {
    MentionSuggestions(
        suggestions = mentionSuggestions,
        emptyState = if (mentionSuggestions.isEmpty()) "No matches" else null,
        onSelect = ::applyMentionSelection,
    )
}
```

Add the composable:

```kotlin
@Composable
private fun MentionSuggestions(
    suggestions: List<ZulipUser>,
    emptyState: String?,
    onSelect: (ZulipUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp()),
    ) {
        if (emptyState != null) {
            LightText(
                text = emptyState,
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
            )
            return@Column
        }
        suggestions.forEach { user ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onSelect(user) }
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
            ) {
                LightText(text = user.fullName, variant = LightTextVariant.Heading)
                if (user.email.isNotBlank()) {
                    LightText(
                        text = user.email,
                        variant = LightTextVariant.Detail,
                    )
                }
            }
        }
    }
}
```

Add the missing imports:

```kotlin
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.zulip.api.models.ZulipUser
```

- [ ] **Step 4: Format confirmed mentions before send**

Change the `SEND` action in `ComposeScreen.kt` from:

```kotlin
{ viewModel.sendMessage(textFieldState.text.toString()) }
```

to:

```kotlin
{
    viewModel.sendMessage(
        formatMessageWithMentions(
            text = textFieldState.text.toString(),
            tokens = mentionTokens,
        ),
    )
}
```

- [ ] **Step 5: Run the focused mention tests and the full Zulip unit suite**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.thelightphone.zulip.ComposeMentionSupportTest \
  --tests com.thelightphone.zulip.ComposeViewModelTest
```

Expected: PASS.

Then run:

```bash
./gradlew :examples:zulip:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit the compose-screen wiring**

```bash
git add \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeScreen.kt \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeViewModel.kt \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeMentionSupport.kt \
  examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeMentionSupportTest.kt \
  examples/zulip/src/test/kotlin/com/thelightphone/zulip/ComposeViewModelTest.kt
git commit -m "feat: add zulip compose mention autocomplete"
```

### Task 4: Verify The Compose Mention Flow On Device

**Files:**
- Verify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeScreen.kt`
- Verify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeMentionSupport.kt`
- Verify command: project root Gradle tasks and emulator install

- [ ] **Step 1: Build and reinstall the Zulip example**

Run:

```bash
./gradlew :examples:zulip:assembleDebug :examples:zulip:installDebug
```

Expected: `BUILD SUCCESSFUL` and `Installed on 1 device.`

- [ ] **Step 2: Manually verify mention autocomplete and send behavior**

Check this sequence:

```text
Open any Zulip conversation
-> COMPOSE
-> type "Hello @a"
-> see mention suggestions above the keyboard
-> tap Alice Adams
-> confirm editor now shows "Hello @Alice Adams "
-> continue typing message text
-> send the message
-> return to the message list
```

Expected:

- suggestions appear as soon as a valid `@query` is active
- suggestion items show full name and email when present
- selected mention inserts readable text plus a trailing space
- continued typing after the mention does not break the editor
- the received/sent message displays `@Alice Adams`, not raw `@**Alice Adams|1**` markup

- [ ] **Step 3: Manually verify graceful degradation**

Check this sequence:

```text
COMPOSE
-> type "@ali"
-> select Alice Adams
-> move the caret into the middle of "@Alice Adams"
-> change one letter
-> send the message
```

Expected:

- the edited text sends as ordinary plain text
- no crash, no frozen keyboard, no malformed markup in the outgoing content

- [ ] **Step 4: Run a final status check**

Run:

```bash
git status --short
```

Expected: only intended Zulip and plan-file changes remain.

- [ ] **Step 5: Commit the verification pass**

```bash
git add examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeScreen.kt
git commit -m "test: verify zulip mention compose flow"
```

---

## Self-Review

### Spec coverage

- Typing `@` triggers autocomplete against realm users: covered in Tasks 1 and 3.
- Suggestions match name and email: covered in Task 1 and rendered in Task 3.
- Selecting a user inserts readable inline text with a trailing space: covered in Tasks 1 and 3.
- Mention tokens survive surrounding edits but invalidate on direct edits: covered in Task 1 and exercised in Task 4.
- Send-time rewrite uses Zulip `@**Full Name|id**` markup: covered in Task 1 and wired in Task 3.
- Invalid or stale mentions degrade to plain text instead of blocking send: covered in Task 1 and verified in Task 4.

### Placeholder scan

No `TODO`, `TBD`, or “similar to previous task” placeholders remain. Every task has exact file targets, concrete test cases, code snippets, and commands.

### Type consistency

The plan consistently uses:

- `ComposeMentionToken`
- `ActiveMentionQuery`
- `MentionInsertionResult`
- `findActiveMentionQuery`
- `filterMentionSuggestions`
- `applyTextEditToMentionTokens`
- `insertMention`
- `formatMessageWithMentions`
- `ComposeUiState.mentionableUsers`

No later task introduces alternate names for the same concepts.
