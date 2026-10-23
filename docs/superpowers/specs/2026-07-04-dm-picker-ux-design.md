# DM Picker UX Design

## Goal

Refine the Zulip direct-message participant picker so it works well on the LightOS screen and keyboard constraints.

The current picker has four usability problems:

- the keyboard obscures too much of the suggestion list
- the search box duplicates the `To:` area and wastes vertical space
- selected names are too long once multiple recipients are chosen
- there is no good way to dismiss the keyboard and inspect results

## Chosen Direction

Use a single `To:` row as both the recipient display and the search input.

This replaces the separate `Search` label and query field. The user interacts with one compact surface:

- selected recipients appear in the `To:` row as initials badges
- the active text query appears inline at the end of the same row
- the caret conceptually lives inside the `To:` row, after the badges

## Interaction Model

### Default state

When the screen opens:

- the `To:` row is visible and empty
- no separate search box is shown
- the results area shows a short recent or relevant user shortlist
- the keyboard is hidden until the user activates the `To:` row

### Starting a search

When the user taps the `To:` row:

- the keyboard opens
- inline query entry begins inside the `To:` row
- the results list filters live from the current query

Matching rules remain:

- match full name first
- also match email when available
- exclude already-selected users

### Adding a participant

When the user taps a suggestion:

- that user is added to the `To:` row as an initials badge
- the inline query is cleared
- the keyboard auto-hides
- the results area remains visible so the user can inspect the shortlist

### Continuing after the first participant

To add another participant:

- the user taps the `To:` row again
- the keyboard reopens
- with an empty query, the results area shows the recent/relevant shortlist again

This preserves a compact browse-search-pick loop without leaving the screen.

### Removing a participant

Tapping an initials badge in the `To:` row removes that participant immediately.

The existing `CLEAR` action may remain as a convenience to remove all selected recipients at once.

### Hiding the keyboard

When the keyboard is open, the bottom bar should expose a `HIDE` action.

`HIDE`:

- dismisses the keyboard
- preserves the current inline query text
- leaves the suggestion list visible

This gives the user a way to inspect results without losing context.

### Proceeding

`NEXT` is enabled once at least one participant is selected and continues to open the existing DM compose flow with `Narrow.Dm(selectedUserIds)`.

## Visual Structure

The screen should be organized as:

1. top bar
2. `To:` row with initials badges and inline query text
3. results list
4. keyboard, only when active
5. bottom bar actions

The old standalone `Search` block is removed entirely.

## Component Changes

### `ChooseParticipantsScreen`

Refactor the screen to:

- render a tappable `To:` input row
- render selected recipients as initials badges
- render inline query text inside the same row
- support keyboard shown/hidden state
- conditionally show `HIDE` when the keyboard is visible
- keep the results list usable whether the keyboard is open or closed

### `ChooseParticipantsViewModel`

Add lightweight UI state for:

- selected user ids
- current query
- keyboard visibility
- recent/relevant shortlist input data, if needed

The view model should own picker state so the interaction does not depend on local composable-only state.

### Suggestion logic

The filtering helpers should support two modes:

- empty query => recent/relevant shortlist
- non-empty query => filtered matches

## Data Source for Shortlist

The empty-query results should come from a recent or relevant shortlist rather than the full user directory.

Initial implementation can use a simple deterministic heuristic if no richer source exists yet, for example:

- recently messaged users first, when available
- otherwise alphabetical fallback, trimmed to a short list

The shortlist only needs to be good enough to avoid an empty screen and reduce scrolling.

## Error Handling and Edge Cases

- If there are no available realm users, show a simple empty-state message.
- If the inline query has no matches, show `No matches`.
- If all shortlist users are already selected, fall back to remaining users or show `No matches`, whichever is simpler and clearer in implementation.
- If a selected recipient is removed, the results list should refresh immediately.

## Testing

Add focused regression coverage for:

- initials generation for selected recipients
- empty-query shortlist behavior
- query filtering after one or more recipients are already selected
- tap-to-remove behavior for selected initials badges
- keyboard visibility state transitions: open, auto-hide after add, manual hide, reopen

Manual verification should cover:

- adding one recipient
- adding multiple recipients
- hiding and reopening the keyboard
- removing a selected recipient
- proceeding to compose with one and multiple recipients

## Out of Scope

- redesigning the downstream compose screen
- changing DM conversation title rules beyond the picker display itself
- adding server-backed ranking or complex people-search relevance
