# DM Picker UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current two-box DM participant picker with a single `To:` row that supports initials badges, inline query text, recent/relevant shortlist results, and keyboard show/hide behavior that preserves visibility of results.

**Architecture:** Keep the heavy interaction logic out of the composable body. Put deterministic query, initials, shortlist, and display formatting in small pure helper functions under `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/`, move picker state ownership into `ChooseParticipantsViewModel`, and make `ChooseParticipantsScreen` a thin renderer that reflects viewmodel state and conditionally mounts the keyboard.

**Tech Stack:** Kotlin, Jetpack Compose, existing LightOS SDK UI components, `LightEmbeddedLp3Keyboard`, `StateFlow`, `kotlin.test`, Gradle unit tests.

---

## File Structure

### Files to modify

- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsScreen.kt`
  - Replace the separate `Search` block with a single tappable `To:` row.
  - Mount/unmount the keyboard based on viewmodel state.
  - Render initials badges, inline query text, shortlist/match results, and `HIDE`/`CLEAR`/`NEXT` actions.
- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsViewModel.kt`
  - Own selected recipients, current query, keyboard visibility, and shortlist ordering.
  - Provide intent-style methods such as activate input, hide keyboard, update query, add/remove recipients, clear all.
- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsState.kt`
  - Hold pure picker-state transition helpers so keyboard/query behavior is unit-testable outside Compose.
- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/DirectMessageComposeSupport.kt`
  - Expand pure helper coverage beyond simple filtering to include initials badges, shortlist selection, and inline row formatting.
- `examples/zulip/src/test/kotlin/com/thelightphone/zulip/DirectMessageComposeSupportTest.kt`
  - Add regression coverage for initials generation, shortlist behavior, and badge removal inputs.
- `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ChooseParticipantsStateTest.kt`
  - Cover keyboard visibility state transitions, query preservation on `HIDE`, and auto-hide after adding a participant.

### Files that may also change

- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/MessagePresentationSupport.kt`
  - Only if it is cleaner to keep picker-only presentation helpers in a dedicated file separate from message-list helpers.
- `examples/zulip/src/test/kotlin/com/thelightphone/zulip/MessagePresentationSupportTest.kt`
  - Only if the shortlist placeholder behavior remains there and needs updated expectations.

### Files to verify but probably not modify

- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeScreen.kt`
  - Confirm `Narrow.Dm(selectedUserIds)` handoff still works unchanged.
- `examples/zulip/src/main/kotlin/com/thelightphone/zulip/store/ZulipStore.kt`
  - Confirm `selectableUsers()` already supplies the active-realm directory for picker input.

---

### Task 1: Expand Pure Picker Helpers First

**Files:**
- Modify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/DirectMessageComposeSupport.kt`
- Test: `examples/zulip/src/test/kotlin/com/thelightphone/zulip/DirectMessageComposeSupportTest.kt`

- [ ] **Step 1: Write the failing tests for initials and shortlist behavior**

Add these tests to `examples/zulip/src/test/kotlin/com/thelightphone/zulip/DirectMessageComposeSupportTest.kt`:

```kotlin
@Test
fun buildsInitialsForSelectedParticipants() {
    assertEquals("AA", participantInitials("Alice Adams"))
    assertEquals("BC", participantInitials("Bob Charles"))
    assertEquals("Q", participantInitials("Q"))
}

@Test
fun emptyQueryUsesShortRelevantListInsteadOfFullDirectory() {
    val shortlist = shortlistParticipantSuggestions(
        users = users,
        selectedUserIds = setOf(1),
        recentUserIds = listOf(3, 2, 99),
        limit = 2,
    )

    assertEquals(listOf(3, 2), shortlist.map { it.userId })
}

@Test
fun shortlistFallsBackToAlphabeticalUsersWhenNoRecentMatchesRemain() {
    val shortlist = shortlistParticipantSuggestions(
        users = users,
        selectedUserIds = setOf(2),
        recentUserIds = emptyList(),
        limit = 2,
    )

    assertEquals(listOf(1, 3), shortlist.map { it.userId })
}
```

- [ ] **Step 2: Run the picker helper tests to verify they fail**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest --tests com.thelightphone.zulip.DirectMessageComposeSupportTest
```

Expected: FAIL with unresolved references for `participantInitials` and `shortlistParticipantSuggestions`.

- [ ] **Step 3: Implement the minimal helper functions**

Update `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/DirectMessageComposeSupport.kt` with these additions:

```kotlin
fun participantInitials(fullName: String): String {
    val parts = fullName
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    if (parts.size == 1) return parts.first().take(1).uppercase()
    return buildString {
        append(parts.first().first().uppercaseChar())
        append(parts.last().first().uppercaseChar())
    }
}

fun shortlistParticipantSuggestions(
    users: List<ZulipUser>,
    selectedUserIds: Set<Int>,
    recentUserIds: List<Int>,
    limit: Int,
): List<ZulipUser> {
    val available = users.filterNot { it.userId in selectedUserIds }
    val byId = available.associateBy { it.userId }
    val recent = recentUserIds.mapNotNull(byId::get)
    if (recent.isNotEmpty()) return recent.distinctBy { it.userId }.take(limit)
    return available
        .sortedBy { it.fullName.lowercase() }
        .take(limit)
}
```

- [ ] **Step 4: Reuse shortlist behavior in the existing filter helper**

Adjust `filterParticipantSuggestions` so empty query no longer means “all users”; it should defer to the shortlist helper:

```kotlin
fun filterParticipantSuggestions(
    users: List<ZulipUser>,
    query: String,
    selectedUserIds: Set<Int>,
    recentUserIds: List<Int> = emptyList(),
    shortlistLimit: Int = 6,
): List<ZulipUser> {
    val trimmed = query.trim().lowercase()
    if (trimmed.isBlank()) {
        return shortlistParticipantSuggestions(
            users = users,
            selectedUserIds = selectedUserIds,
            recentUserIds = recentUserIds,
            limit = shortlistLimit,
        )
    }
    return users
        .asSequence()
        .filterNot { it.userId in selectedUserIds }
        .filter { user ->
            user.fullName.lowercase().contains(trimmed) ||
                user.email.lowercase().contains(trimmed)
        }
        .sortedBy { it.fullName.lowercase() }
        .toList()
}
```

- [ ] **Step 5: Run the helper tests to verify they pass**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest --tests com.thelightphone.zulip.DirectMessageComposeSupportTest
```

Expected: PASS.

- [ ] **Step 6: Commit the helper-only change**

```bash
git add \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/DirectMessageComposeSupport.kt \
  examples/zulip/src/test/kotlin/com/thelightphone/zulip/DirectMessageComposeSupportTest.kt
git commit -m "test: cover dm picker shortlist helpers"
```

### Task 2: Move Picker Interaction State Into the ViewModel

**Files:**
- Create: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsState.kt`
- Modify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsViewModel.kt`
- Modify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/DirectMessageComposeSupport.kt`
- Test: `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ChooseParticipantsStateTest.kt`
- Test: `examples/zulip/src/test/kotlin/com/thelightphone/zulip/DirectMessageComposeSupportTest.kt`

- [ ] **Step 1: Write failing tests for keyboard/query state transitions**

Create `examples/zulip/src/test/kotlin/com/thelightphone/zulip/ChooseParticipantsStateTest.kt` with:

```kotlin
class ChooseParticipantsStateTest {
    @Test
    fun activateInputShowsKeyboard() {
        val updated = PickerUiState().activateInput()

        assertTrue(updated.isKeyboardVisible)
    }

    @Test
    fun hideKeyboardPreservesCurrentQuery() {
        val updated = PickerUiState(query = "ali", isKeyboardVisible = true).hideKeyboard()

        assertEquals("ali", updated.query)
        assertFalse(updated.isKeyboardVisible)
    }

    @Test
    fun addParticipantClearsQueryAndHidesKeyboardAndPromotesRecentUser() {
        val updated = PickerUiState(
            selectedUserIds = listOf(1),
            query = "bob",
            isKeyboardVisible = true,
            recentUserIds = listOf(3),
        ).addParticipant(2)

        assertEquals(listOf(1, 2), updated.selectedUserIds)
        assertEquals("", updated.query)
        assertFalse(updated.isKeyboardVisible)
        assertEquals(listOf(2, 3), updated.recentUserIds)
    }

    @Test
    fun removeParticipantDropsOnlyTheTappedUser() {
        val updated = PickerUiState(selectedUserIds = listOf(1, 2, 3)).removeParticipant(2)

        assertEquals(listOf(1, 3), updated.selectedUserIds)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest --tests com.thelightphone.zulip.ChooseParticipantsStateTest
```

Expected: FAIL with unresolved references for `PickerUiState`, `activateInput`, `hideKeyboard`, `addParticipant`, and `removeParticipant`.

- [ ] **Step 3: Implement the pure picker state helper**

Create `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsState.kt`:

```kotlin
data class PickerUiState(
    val selectedUserIds: List<Int> = emptyList(),
    val query: String = "",
    val isKeyboardVisible: Boolean = false,
    val recentUserIds: List<Int> = emptyList(),
)

fun PickerUiState.activateInput(): PickerUiState =
    copy(isKeyboardVisible = true)

fun PickerUiState.hideKeyboard(): PickerUiState =
    copy(isKeyboardVisible = false)

fun PickerUiState.updateQuery(value: String): PickerUiState =
    copy(query = value)

fun PickerUiState.addParticipant(userId: Int): PickerUiState {
    if (userId in selectedUserIds) return this
    return copy(
        selectedUserIds = selectedUserIds + userId,
        query = "",
        isKeyboardVisible = false,
        recentUserIds = listOf(userId) + recentUserIds.filterNot { it == userId },
    )
}

fun PickerUiState.removeParticipant(userId: Int): PickerUiState =
    copy(selectedUserIds = selectedUserIds.filterNot { it == userId })

fun PickerUiState.clearParticipants(): PickerUiState =
    copy(selectedUserIds = emptyList(), query = "")
```

- [ ] **Step 4: Add a failing badge-display test before wiring the UI**

Append this test to `examples/zulip/src/test/kotlin/com/thelightphone/zulip/DirectMessageComposeSupportTest.kt`:

```kotlin
@Test
fun buildsBadgeDisplayForSelectedParticipants() {
    val badges = buildSelectedParticipantBadges(
        selectedUserIds = listOf(2, 1),
        usersById = users.associateBy { it.userId },
    )

    assertEquals(listOf("BB", "AA"), badges.map { it.initials })
}
```

- [ ] **Step 5: Run the badge test to verify it fails**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest --tests com.thelightphone.zulip.DirectMessageComposeSupportTest.buildsBadgeDisplayForSelectedParticipants
```

Expected: FAIL with unresolved reference for `buildSelectedParticipantBadges`.

- [ ] **Step 6: Implement the badge display model**

Add this code to `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/DirectMessageComposeSupport.kt`:

```kotlin
data class SelectedParticipantBadge(
    val userId: Int,
    val initials: String,
)

fun buildSelectedParticipantBadges(
    selectedUserIds: List<Int>,
    usersById: Map<Int, ZulipUser>,
): List<SelectedParticipantBadge> = selectedUserIds.map { userId ->
    val initials = usersById[userId]
        ?.fullName
        ?.let(::participantInitials)
        ?: "U$userId"
    SelectedParticipantBadge(
        userId = userId,
        initials = initials,
    )
}
```

- [ ] **Step 7: Expand the viewmodel state so it delegates to the pure helper**

Update `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsViewModel.kt` to carry the full picker state:

```kotlin
data class ChooseParticipantsUiState(
    val users: List<ZulipUser> = emptyList(),
    val selectedUserIds: List<Int> = emptyList(),
    val query: String = "",
    val isKeyboardVisible: Boolean = false,
    val recentUserIds: List<Int> = emptyList(),
)
```

Then route all intents through `PickerUiState`:

```kotlin
private fun ChooseParticipantsUiState.toPickerState() = PickerUiState(
    selectedUserIds = selectedUserIds,
    query = query,
    isKeyboardVisible = isKeyboardVisible,
    recentUserIds = recentUserIds,
)

private fun ChooseParticipantsUiState.fromPickerState(picker: PickerUiState) = copy(
    selectedUserIds = picker.selectedUserIds,
    query = picker.query,
    isKeyboardVisible = picker.isKeyboardVisible,
    recentUserIds = picker.recentUserIds,
)
```

- [ ] **Step 8: Implement the intent methods with the pure transition helper**

Finish the viewmodel methods in `ChooseParticipantsViewModel.kt`:

```kotlin
fun activateInput() {
    _uiState.update { state -> state.fromPickerState(state.toPickerState().activateInput()) }
}

fun hideKeyboard() {
    _uiState.update { state -> state.fromPickerState(state.toPickerState().hideKeyboard()) }
}

fun updateQuery(value: String) {
    _uiState.update { state -> state.fromPickerState(state.toPickerState().updateQuery(value)) }
}

fun addParticipant(userId: Int) {
    _uiState.update { state -> state.fromPickerState(state.toPickerState().addParticipant(userId)) }
}

fun removeParticipant(userId: Int) {
    _uiState.update { state -> state.fromPickerState(state.toPickerState().removeParticipant(userId)) }
}

fun clearParticipants() {
    _uiState.update { state -> state.fromPickerState(state.toPickerState().clearParticipants()) }
}
```

- [ ] **Step 9: Run the state and helper tests again**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.thelightphone.zulip.ChooseParticipantsStateTest \
  --tests com.thelightphone.zulip.DirectMessageComposeSupportTest
```

Expected: PASS.

- [ ] **Step 10: Commit the picker state change**

```bash
git add \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsState.kt \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsViewModel.kt \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/DirectMessageComposeSupport.kt \
  examples/zulip/src/test/kotlin/com/thelightphone/zulip/ChooseParticipantsStateTest.kt \
  examples/zulip/src/test/kotlin/com/thelightphone/zulip/DirectMessageComposeSupportTest.kt
git commit -m "feat: move dm picker state into viewmodel"
```

### Task 3: Refactor the Screen to a Single `To:` Row

**Files:**
- Modify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsScreen.kt`
- Modify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsState.kt`
- Modify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsViewModel.kt`
- Modify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/DirectMessageComposeSupport.kt`

- [ ] **Step 1: Refactor the screen body to render the inline `To:` row**

Replace the separate summary and search blocks in `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsScreen.kt` with a single tappable row:

```kotlin
val badges = buildSelectedParticipantBadges(state.selectedUserIds, usersById)
val suggestions = filterParticipantSuggestions(
    users = state.users,
    query = state.query,
    selectedUserIds = state.selectedUserIds.toSet(),
    recentUserIds = state.recentUserIds,
)

Column(
    modifier = Modifier
        .fillMaxWidth()
        .lightClickable { viewModel.activateInput() }
        .padding(horizontal = 1f.gridUnitsAsDp()),
) {
    LightText(text = "To", variant = LightTextVariant.Detail)
    ParticipantInputRow(
        badges = badges,
        query = state.query,
        onBadgeClick = viewModel::removeParticipant,
    )
}
```

- [ ] **Step 2: Only show the keyboard while the input is active**

Use the viewmodel’s `isKeyboardVisible` flag and route key presses back into `updateQuery`:

```kotlin
val queryState = rememberTextFieldState(state.query)
LaunchedEffect(state.query) {
    if (queryState.text.toString() != state.query) {
        queryState.edit {
            replace(0, length, state.query)
            selection = TextRange(state.query.length)
        }
    }
}

LaunchedEffect(queryState.text.toString()) {
    if (queryState.text.toString() != state.query) {
        viewModel.updateQuery(queryState.text.toString())
    }
}

if (state.isKeyboardVisible) {
    LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)
}
```

- [ ] **Step 3: Update the suggestion result area and bottom bar actions**

Adjust the same file so results remain visible with or without the keyboard, and add the `HIDE` action:

```kotlin
LightBottomBar(
    items = listOf(
        LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = { goBack() },
            contentDescription = "Back",
        ),
        when {
            state.isKeyboardVisible -> LightBarButton.Text(
                text = "HIDE",
                onClick = viewModel::hideKeyboard,
            )
            state.selectedUserIds.isNotEmpty() -> LightBarButton.Text(
                text = "CLEAR",
                onClick = viewModel::clearParticipants,
            )
            else -> null
        },
        if (state.selectedUserIds.isEmpty()) {
            null
        } else {
            LightBarButton.Text(
                text = "NEXT",
                onClick = {
                    navigateTo(
                        screenFactory = { ComposeScreen(it, Narrow.Dm(state.selectedUserIds)) },
                    )
                },
            )
        },
    ),
)
```

- [ ] **Step 4: Add a small composable for the badges**

Keep `ChooseParticipantsScreen.kt` readable by extracting the row rendering within the same file first:

```kotlin
@Composable
private fun ParticipantInputRow(
    badges: List<SelectedParticipantBadge>,
    query: String,
    onBadgeClick: (Int) -> Unit,
) {
    Column {
        badges.forEach { badge ->
            LightText(
                text = badge.initials,
                variant = LightTextVariant.Heading,
                modifier = Modifier.lightClickable { onBadgeClick(badge.userId) },
            )
        }
        LightText(
            text = if (query.isBlank()) "Type a name or email" else query,
            variant = LightTextVariant.Copy,
        )
    }
}
```

- [ ] **Step 5: Run the picker state tests and the full Zulip unit test suite**

Run:

```bash
./gradlew :examples:zulip:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit the screen refactor**

```bash
git add \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsScreen.kt \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsState.kt \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsViewModel.kt \
  examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/DirectMessageComposeSupport.kt \
  examples/zulip/src/test/kotlin/com/thelightphone/zulip/ChooseParticipantsStateTest.kt \
  examples/zulip/src/test/kotlin/com/thelightphone/zulip/DirectMessageComposeSupportTest.kt
git commit -m "feat: refactor dm picker into to-row input"
```

### Task 4: Verify the Full DM Flow on Device

**Files:**
- Verify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsScreen.kt`
- Verify: `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeScreen.kt`
- Verify command: project root Gradle tasks and emulator install

- [ ] **Step 1: Build and reinstall the Zulip example**

Run:

```bash
./gradlew :examples:zulip:assembleDebug :examples:zulip:installDebug
```

Expected: `BUILD SUCCESSFUL` and `Installed on 1 device.`

- [ ] **Step 2: Manually verify the picker interaction on the emulator**

Check this sequence:

```text
Direct messages
-> compose
-> tap To:
-> keyboard appears
-> shortlist is visible
-> type a query
-> select one person
-> keyboard hides
-> tap To: again
-> keyboard reopens
-> shortlist or filtered results remain usable
-> tap initials badge to remove
-> tap HIDE to inspect results
-> tap NEXT to open Compose
```

Expected:

- no separate `Search` box
- selected recipients show as initials
- keyboard can be hidden without clearing query
- second and later searches are possible without the keyboard permanently blocking results

- [ ] **Step 3: Run a final status check**

Run:

```bash
git status --short
```

Expected: only intended Zulip and plan-file changes remain.

- [ ] **Step 4: Commit the verification pass**

```bash
git add examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ChooseParticipantsScreen.kt
git commit -m "test: verify dm picker ux flow"
```

---

## Self-Review

### Spec coverage

- Single `To:` row replaces the extra search field: covered in Task 3.
- Selected participants shown as initials badges: covered in Tasks 1, 2, and 3.
- Empty query shows recent/relevant shortlist: covered in Tasks 1 and 3.
- Keyboard auto-hides after add, can reopen, and can be hidden manually: covered in Tasks 2 and 3.
- Badge tap removes participants: covered in Tasks 2 and 3.
- Existing `NEXT` compose handoff remains: covered in Task 3 and verified in Task 4.

### Placeholder scan

No `TODO`, `TBD`, or “similar to previous task” placeholders remain. Every task has concrete file targets, code snippets, and commands.

### Type consistency

The plan consistently uses:

- `ChooseParticipantsUiState.query`
- `ChooseParticipantsUiState.isKeyboardVisible`
- `ChooseParticipantsUiState.recentUserIds`
- `PickerUiState`
- `participantInitials`
- `shortlistParticipantSuggestions`
- `buildSelectedParticipantBadges`

No later task introduces alternate names for the same concepts.
