# Task 6: Classify Failures and Process Outbox Messages

## Status

Implemented typed Zulip transport and HTTP errors plus ordered durable outbox processing.

## Delivered

- `ApiError` now retains the HTTP status; `Network` retains an optional underlying cause.
- `ZulipClient` preserves cancellation and wraps I/O and request-timeout transport failures as `Network`.
- Added transient/permanent failure classification for network, 408, 429, and 5xx failures.
- Added `ZulipOutboxProcessor`: resets interrupted sends, processes queued rows in creation order, checkpoints uploads, reuses checkpointed URLs, sends final Markdown, completes successful rows, retries transient failures, and continues after permanent failures.
- Added TDD coverage for classification, upload order, checkpoint retry reuse, Markdown construction, cleanup, permanent continuation, and ambiguous send timeouts.

## Verification

- Red: focused outbox tests failed before implementation with unresolved processor/classifier symbols and missing typed exception constructors.
- Green: `./gradlew :examples:zulip:testDebugUnitTest --tests com.jhellerstedt.zulip.OutboxFailureClassifierTest --tests com.jhellerstedt.zulip.ZulipOutboxProcessorTest --tests com.jhellerstedt.zulip.RegisterCompatibilityTest` passed.
- Full: `./gradlew :examples:zulip:testDebugUnitTest` passed.

## Concern

An ambiguous timeout while sending leaves the message queued for retry; this intentionally does not assert exactly-once delivery because the server may have accepted the send.

## Commit

`75842ad feat: process zulip outbox with retry classification`

## Important Finding Follow-up: ZulipClient 5xx Response Handling

### Status

Fixed. `ZulipClient` now reads response bodies through its cancellation-preserving network wrapper, so body-read `IOException`s become `Network` errors with their cause retained. For 408, 429, and 5xx responses, malformed or non-Zulip bodies now become `ApiError(status, null, "HTTP <status>")`, which the outbox classifier treats as transient. Valid Zulip error JSON retains its code and message. Broken 2xx bodies remain `MalformedResponse`.

### Verification

- Red: `./gradlew :examples:zulip:testDebugUnitTest --tests com.jhellerstedt.zulip.ZulipClientTest` failed because a malformed 503 response was `MalformedResponse`, not `ApiError`.
- Green: `./gradlew :examples:zulip:testDebugUnitTest --tests com.jhellerstedt.zulip.ZulipClientTest --tests com.jhellerstedt.zulip.OutboxFailureClassifierTest` passed.
- Full: `./gradlew :examples:zulip:testDebugUnitTest` passed.
