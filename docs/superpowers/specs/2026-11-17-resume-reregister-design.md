# Re-register event sync after a long pause

## Goal

Opening Zulip after the process has been paused for 10 minutes or more fetches a fresh `unread_msgs` snapshot before polling resumes. The notification asterisk updates from that snapshot instead of waiting for a poll of a dead queue to fail.

## Scope

This design covers `GlobalStore` in `tool/src/main/kotlin/com/jhellerstedt/zulip/store/GlobalStore.kt` and its tests in `tool/src/test/kotlin/com/jhellerstedt/zulip/GlobalStoreTest.kt`.

It includes:

- recording the first pause time in `pauseEventSync`
- re-registering from `resumeEventSync` when that pause lasted 10 minutes or more
- starting the poller after that register only when the same store is still active and sync is not paused
- an injectable clock so tests can advance pause duration

It does not include:

- `LightWork.enqueuePeriodic` background refresh
- UnifiedPush
- persisting the pause timestamp across process death
- logging pause, show, or register events for a device run
- changing how LightOS delivers `onAppPause`

A process kill still hydrates from cache in `switchAccountLocked` and registers in the background when cached state exists. This path covers a live process that was paused and then shown again.

## Behavior

`GlobalStore` takes `nowMillis: () -> Long = { System.currentTimeMillis() }`. Production construction in `ZulipServices.globalStore` keeps the default. The threshold is `EVENT_SYNC_REREGISTER_AFTER_PAUSE_MILLIS = 10 * 60 * 1000` milliseconds, visible to tests.

`resumeEventSync` stays a non-suspending function. `onScreenShow` does not wait on the network. The screen can paint the cached asterisk until `coordinator.register` applies `unread_msgs`.

### Pause

`pauseEventSync` stops the poller and sets `eventSyncPaused`. The first call while sync is running stores `nowMillis()` in `eventSyncPausedAtMillis`. A second pause while already paused leaves that timestamp unchanged and stops the poller again.

### Resume under 10 minutes

If there is no stored pause time, or the elapsed time is under 10 minutes, `resumeEventSync` clears the pause flag and timestamp, then calls `startEventSyncIfNeeded`. That restarts the existing queue when the poller is stopped.

If a long-pause register launched by an earlier resume is still running, this resume does not start the poller. The in-flight register starts it when it finishes, subject to the guards below.

### Resume at 10 minutes or more

`resumeEventSync` clears the pause flag and timestamp, cancels any in-flight resume register, and launches `coordinator.register(store)` on the store scope. It does not start the poller before that register returns.

When the register call returns, the coroutine starts the poller only if all of these hold:

- the coroutine is still active
- `_activeStore.value` is still that same store instance
- `eventSyncPaused` is false

Both a successful and a failed register use those guards. Success applies the new queue and `unread_msgs` through the existing register path, then polls the new queue. Failure leaves the previous queue id in place and still starts the poller, so `EventPoller` keeps its retry and `BAD_EVENT_QUEUE_ID` recovery. A cancelled register does not start the poller.

`switchAccountLocked` does not cancel this job. A register that finishes after an account switch still updates that account, and the active-store guard keeps it from starting the poller. That matches the existing background register on activation.

## Tests

`GlobalStoreTest` supplies the fake clock. Existing pause and resume tests stay on the short path because they resume immediately.

Add these cases:

- A pause under 10 minutes resumes the same queue and does not call register again.
- A pause of 10 minutes calls register again and the next poll uses the new queue id, with no poll of the old queue on that resume.
- Pausing again while the long-pause register is blocked, then completing it, does not start the poller.
- Switching accounts while the long-pause register is blocked, then completing it, does not poll the old account's new queue.
- Pause, advance 9 minutes, pause again, advance 2 more minutes, then resume. The second pause keeps the original timestamp, so resume re-registers.
- A long-pause register that fails still polls the previous queue.
- A second long-pause resume cancels the in-flight register. Only the replacement register starts the poller, on its own queue id.

## Why 10 minutes

Servers that do not accept `idle_queue_timeout` drop the queue after 10 minutes without a poll. Re-registering at that point skips the failed poll. Servers at feature level 481 or newer keep a `mobile` queue for 12 hours; this resume still replaces `unread_msgs` from the register response after the same cutoff.
