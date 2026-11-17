# Home button and recent shortcuts

## Goal

A HOME control in the bottom center returns to one place. With exactly one saved realm, that place is the Streams / Direct messages menu. With any other count, it is the realm list. The menu also lists the two most recently active streams under Streams, and the two most recently active direct-message conversations under Direct messages.

## Scope

This design covers:

- returning home with `goBack` and `navigateTo`, the navigation the published SDK allows the portal build to use
- the HOME control on the browsing screens listed below
- shortcut rows on `ZulipHomeScreen`
- the account-count rule that picks the destination

It does not include:

- changing cold start. The app still restores the last realm and leaves the realm list underneath until HOME is pressed
- a house icon. The control is the word HOME
- HOME on the compose screen, the participant picker, login, the expanded-photo overlay, or the outbox-failure overlay
- a new sort. Shortcuts use the order `ZulipStore` already publishes
- switching accounts when HOME is pressed

## Destination

```kotlin
fun homeDestination(accountCount: Int): HomeDestination
```

`HomeDestination` is either `Menu` or `RealmList`.

| Saved accounts | Destination |
| --- | --- |
| 1 | `ZulipHomeScreen` |
| 0, 2, or more | `ChooseAccountScreen` constructed with `autoRestoreActiveAccount = false` |

The count is `GlobalStore.listAccounts().size`, read on `Dispatchers.IO`. `openHome` then runs on the main thread. A throw from that read uses `RealmList`.

HOME does not call `switchAccount` or `initialize`. It shows the menu for the account that is already active. If none is active, the menu uses the empty state it already has.

## Stack reset

The portal compiles `tool/` against the published Light SDK and rejects reflection. That SDK exposes `navigateTo` and `goBack` only, so Home cannot replace the back stack.

With one saved realm, Home sets a return flag and goes back one screen. Each browsing screen above the menu sees the flag when it is shown and goes back again, until the Streams / Direct messages menu clears the flag. With any other count, Home opens the realm list on top and does not restore the last realm. Back from that list returns to the screen underneath it.

`openHome` does not go through `LightViewModel.onBackPressed` beyond the normal `goBack` path. No view model in this tool consumes back.

A second tap while a HOME navigation is in flight does nothing. A failed count read still continues into `openHome` for the realm list. The in-flight check ends when `openHome` runs, or when the screen's coroutine is cancelled before that.

After Home with one saved realm, Back from the menu follows the stack that was already under it.

## Where HOME appears

The control is `LightBarButton.Text` with the label `HOME`, in the center slot of `LightBottomBar`.

Always shown:

| Screen | Bottom bar, left to right |
| --- | --- |
| Stream list | Back, HOME, empty |
| Topic list | Back, HOME, empty |
| Message list | Back, HOME, COMPOSE |
| Direct messages | Back, HOME, compose icon |
| Settings | empty, HOME, empty |
| Data disclosure | empty, HOME, empty |

Settings and data disclosure keep Back on the top bar. On the message list, COMPOSE moves from the center slot to the right slot. On the direct-message list, the compose icon stays on the right.

Shown only when this screen is not already the destination:

| Screen | When the center slot is HOME |
| --- | --- |
| Streams / Direct messages menu | Saved account count is not 1. Settings stays on the left. |
| Realm list | Saved account count is 1, loading has finished, and the screen is not already navigating to the menu or to login. ADD stays on the left. |

While the menu does not yet know the count, its center slot stays empty. The realm list uses the account list it already loaded; it does not fetch the count a second time.

These screens do not get HOME: login, compose, choose participants, the expanded-photo overlay, the outbox-failure overlay.

## Menu shortcuts

Under the existing Streams row, show the first two entries of `ZulipStore.streamsList`. Under the existing Direct messages row, show the first two entries of `ZulipStore.recentDirectMessages`. Both lists are already newest-first. A stream with no observed message sorts after streams that have one, then by name, and can fill a slot when fewer than two streams have messages.

Zero matches show no shortcut row. One match shows one row. The section row still opens the full list and still shows its count and section-level unread asterisk.

Each shortcut is its own row, under the section row, with one extra grid unit of start padding. The label uses `LightTextVariant.Heading` and `unreadLabel`, same asterisk rule as the full list. Stream shortcuts omit the stream description.

- A stream shortcut opens `TopicListScreen` for that stream.
- A direct-message shortcut opens `MessageListScreen` for that `Narrow.Dm`.

Titles and unread flags for direct messages match `DirectMessageListViewModel`: `Narrow.Dm.displayTitle` for the title, and unread when that narrow's unread count is greater than zero. A stream is unread when its id is in `unreadStreamIds()`.

`ZulipHomeViewModel` exposes the two lists and the menu's `showHome` flag on its existing UI state. It refreshes `showHome` from `listAccounts()` in `onScreenShow`. Shortcut rows follow the store flows the menu already observes.

## Tests

`homeDestination` is a pure function. Its test covers counts 0, 1, and 2.

`ZulipHomeViewModelTest` covers:

- three streams in store order produce the first two as shortcuts
- one stream produces one shortcut
- no streams produce an empty shortcut list
- a stream unread flag follows `unreadStreamIds()`
- three direct-message conversations produce the first two, with the same titles and unread flags as the direct-message list
- one saved account sets `showHome` false; two saved accounts set it true

No instrumentation test is added for the walk back to the menu. The stack contract above is the behavior to implement.
