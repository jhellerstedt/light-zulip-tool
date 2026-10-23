# Zulip Mentions In Compose

## Goal

Add user mention autocomplete to the Zulip compose screen so typing `@` offers matching realm users and selecting one inserts a readable mention that is converted to Zulip-safe markup when the message is sent.

## Scope

This design covers the existing message composer in `examples/zulip/src/main/kotlin/com/thelightphone/zulip/ui/ComposeScreen.kt`.

It includes:

- user mention autocomplete driven by realm users
- readable inline mention text inside the plain-text editor
- send-time conversion to Zulip mention markup with user IDs

It does not include:

- user-group mentions
- wildcard mentions like `@**all**`
- silent mentions with `@_`
- rich chip rendering or a full rich-text editor rewrite

## Chosen Direction

Use readable inline mentions in the editor and keep a parallel token list that tracks which text ranges represent confirmed mentions.

When the user selects a suggestion, the editor should display readable text such as `@Ariella Drake`. Internally, the app should store the selected Zulip user ID and the text range that belongs to that mention. On send, only still-valid tokens are rewritten to Zulip markup such as `@**Ariella Drake|26**`.

This keeps the compose experience legible while still sending the safest Zulip syntax for users whose display names may collide.

## Interaction Model

### Triggering autocomplete

Mention search begins when the caret is inside an active `@query` fragment in the editor.

An active mention fragment is:

- introduced by typing `@` at the start of the message or after whitespace, a newline, or simple opening punctuation
- continued by subsequent non-whitespace characters
- cancelled when the user inserts whitespace or a newline before confirming a mention

For the first iteration, the fragment can be recognized with simple plain-text rules rather than full markdown awareness. The goal is to support the common case of typing `@` in normal message text.

### Suggestion behavior

While a mention fragment is active:

- the composer shows a suggestion list of matching realm users
- matches are based on the existing Zulip user list already stored in the app
- matching prefers full name and also checks email, following the same pattern already used in the DM participant picker

If there are no matches, the suggestion list may show a simple empty state such as `No matches`.

### Selecting a user

Choosing a suggestion replaces the active `@query` fragment with readable text of the form `@Full Name` and appends a trailing space for continued typing.

After insertion:

- the caret moves to immediately after the inserted trailing space
- the mention token is recorded with the user ID, display text, and range for just the `@Full Name` portion
- the suggestion list closes until the user types another `@`

### Editing after insertion

Mention tokens remain valid only while the text for that token still exactly matches the inserted mention range.

If the user types inside a mention, deletes part of it, or otherwise changes that range directly, the token is discarded and the edited text becomes ordinary plain text. This avoids trying to maintain partially edited structured mentions in a plain-text editor.

Typing before or after a valid token should shift its stored range so the token remains attached to the same text span.

## UI Structure

The compose screen remains a plain-text message editor. The new UI element is a lightweight suggestions panel that appears only while an active mention fragment exists.

The screen should behave as follows:

1. top bar remains unchanged
2. message editor remains the main body
3. mention suggestions appear only during active `@query` entry, positioned above the keyboard area so they do not cover the current line of typing
4. keyboard remains the existing embedded keyboard
5. send action remains the same from the user perspective

For the first iteration, the suggestion list should be a simple vertical list rendered in the compose screen using existing text primitives, not a custom overlay system.

## Data Model

Add lightweight mention state owned by the compose screen or compose view model:

- `ComposeMentionToken`
  - `userId: Int`
  - `displayText: String`
  - `start: Int`
  - `endExclusive: Int`

- `ActiveMentionQuery`
  - `start: Int`
  - `endExclusive: Int`
  - `query: String`

The active query is derived from current editor text and selection. Confirmed mention tokens are durable state that survive additional typing until invalidated by direct edits inside their ranges.

The token list should be kept sorted by start offset. This keeps send-time rewriting deterministic and makes range updates simpler.

## Send Pipeline

Before calling the Zulip send API:

1. inspect the current editor text
2. validate each stored token against the current text slice at its recorded range
3. discard any token whose range is out of bounds or whose text no longer matches `@Full Name`
4. rewrite valid tokens from the end of the string backward into Zulip markup
5. send the rewritten result

Backward rewriting avoids offset drift while replacing text.

The Zulip format used for user mentions should be:

- `@**Full Name|userId**`

Using the user ID is important because Zulip supports duplicate display names and prefers ID-qualified mentions for unambiguous resolution.

## Component Changes

### `ComposeScreen`

Extend the screen to:

- derive the active mention query from editor text and caret position
- render mention suggestions when a query is active
- support picking a suggestion and replacing the current `@query` text
- keep mention-token state synchronized with text edits

### `ComposeViewModel`

Keep the existing send flow, but accept or construct final content that has already been rewritten from readable mentions to Zulip markup.

If state ownership is cleaner in the view model, it may also hold the token list. The important boundary is that send-time formatting logic should be deterministic and testable outside the composable body.

### New support file

Create a small pure helper module for mention logic, similar in spirit to the existing compose and participant support helpers. It should own:

- active-query detection
- user filtering
- mention insertion/replacement calculations
- token validation and send-time rewrite

This keeps text-manipulation logic out of the composable and makes unit tests straightforward.

## Error Handling

If mention conversion cannot validate a stored token, the token should be dropped and the visible text should be sent as plain text rather than blocking send.

If the composer cannot resolve a user list, mention autocomplete should simply not appear. Normal plain-text message composition must still work.

The feature should fail soft:

- mention autocomplete unavailable -> plain text still works
- token invalidated by edits -> plain text still works
- duplicate names -> send with ID-qualified syntax when the mention was selected from autocomplete

## Testing

Add focused unit tests for the mention helper logic before implementation.

Minimum coverage:

- detecting an active mention query from plain editor text
- filtering users by name and email
- replacing `@query` with readable `@Full Name` text
- creating the correct token range after insertion
- shifting token ranges when text is inserted before or after them
- invalidating a token when its text is edited directly
- rewriting one confirmed token to `@**Full Name|id**`
- rewriting multiple tokens in one message without offset corruption
- leaving unconfirmed plain text like `@someone` unchanged

UI behavior can rely on existing manual verification after unit coverage proves the text-model logic.

## Non-Goals For First Pass

The first pass should stay intentionally narrow:

- no highlighting or special styling for confirmed mentions inside the editor
- no mention persistence across draft restoration
- no separate keyboard navigation model for suggestion selection unless the existing Light interaction pattern already makes it easy
- no attempt to parse every Zulip markdown edge case

## Success Criteria

The feature is successful when:

- typing `@` in the composer opens matching user suggestions
- selecting a user inserts readable inline text
- messages send ID-qualified Zulip mention markup for confirmed mentions
- ordinary plain-text editing remains reliable even after mentions are inserted
- broken or partially edited mentions degrade gracefully to plain text
