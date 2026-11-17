# Resume Re-register Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a pause of 10 minutes or more, `resumeEventSync` re-registers before polling so `unread_msgs` is fresh on open.

**Architecture:** `GlobalStore` records the first pause timestamp with an injectable clock. A short resume restarts the existing poller. A long resume launches `coordinator.register` on the store scope and starts the poller only when that coroutine is still active, the same store is active, and sync is not paused.

**Tech Stack:** Kotlin, kotlinx-coroutines-test, existing `GlobalStore` / `ZulipSyncCoordinator` / `EventPoller`.

## Global Constraints

- Threshold is `EVENT_SYNC_REREGISTER_AFTER_PAUSE_MILLIS = 10 * 60 * 1000` milliseconds. A pause of that length or more re-registers.
- `resumeEventSync` stays non-suspending.
- A second pause while already paused keeps the original timestamp.
- A failed register still starts the poller when the store is active and sync is not paused.
- A cancelled register does not start the poller.
- `switchAccountLocked` does not cancel the resume register. The active-store guard keeps a late completion from starting the poller.
- No periodic work, push, persisted pause time, or pause/show/register logs.

---

### Task 1: Failing tests

**Files:**
- Modify: `tool/src/test/kotlin/com/jhellerstedt/zulip/GlobalStoreTest.kt`
- Test: `tool/src/test/kotlin/com/jhellerstedt/zulip/GlobalStoreTest.kt`

**Interfaces:**
- Consumes: `GlobalStore.pauseEventSync`, `GlobalStore.resumeEventSync`, `FakeGlobalGateway`
- Produces: tests that require `GlobalStore(..., nowMillis: () -> Long = { System.currentTimeMillis() })` and `EVENT_SYNC_REREGISTER_AFTER_PAUSE_MILLIS`

- [x] **Step 1: Write the failing tests**

Cover short pause, exact 10-minute pause, pause-again during a blocked register, account switch during a blocked register, repeated pause keeping the first timestamp, a failed register, a second long resume cancelling the in-flight register, and a short resume that does not poll while that register is in flight.

`FakeGlobalGateway.register` throws `registerError` after the block check.

- [x] **Step 2: Run the tests**

Run: `./gradlew :tool:testDebugUnitTest --tests com.jhellerstedt.zulip.GlobalStoreTest`

Expected: compile failure, because `nowMillis` and `EVENT_SYNC_REREGISTER_AFTER_PAUSE_MILLIS` do not exist yet.

### Task 2: Pause timestamp and re-register

**Files:**
- Modify: `tool/src/main/kotlin/com/jhellerstedt/zulip/store/GlobalStore.kt`

**Interfaces:**
- Consumes: `ZulipSyncCoordinator.register`, `EventPoller.start` / `stop`
- Produces: `internal const val EVENT_SYNC_REREGISTER_AFTER_PAUSE_MILLIS` and `GlobalStore` parameter `nowMillis: () -> Long = { System.currentTimeMillis() }`

- [x] **Step 1: Implement the minimal behavior**

`pauseEventSync` stores `nowMillis()` only when sync is not already paused, then stops the poller.

`resumeEventSync` clears the pause flag and timestamp. At or above the threshold it cancels any in-flight resume register, launches `coordinator.register(store)`, and returns. After that call returns, it starts the poller only if the coroutine is active, `_activeStore.value` is the same store, and `eventSyncPaused` is false. Under the threshold, it starts the poller immediately unless a resume register is still active.

- [x] **Step 2: Run the tests**

Run: `./gradlew :tool:testDebugUnitTest --tests com.jhellerstedt.zulip.GlobalStoreTest`

Expected: PASS
