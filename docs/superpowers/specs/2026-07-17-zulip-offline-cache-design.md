# Zulip Offline Cache and Durable Outbox

## Goal

Make the Zulip example resilient to unreachable, high-latency, and low-upload-capacity self-hosted servers by showing recent cached data immediately and reliably retrying messages and attachments.

## Scope

This design covers the Zulip example under `examples/zulip`.

It includes:

- a persistent, account-scoped cache for streams, topics, users, conversations, unread state, and recent messages
- stale-while-revalidate loading for account startup and message lists
- a durable outbox for text messages and attachments
- background retries through the SDK's existing `LightWork` API
- non-blocking offline and reconnecting status
- cache retention of the 25 most recent messages per conversation for seven days

It does not include:

- caching uploaded remote files or inline images for offline viewing
- downloading message history that the user has never opened
- changing server DNS, VPN, TLS, or local-network configuration
- exactly-once delivery guarantees beyond what the Zulip API provides
- a standalone outbox screen
- changing the existing event long-poll protocol

## Chosen Direction

Use Room as the durable source and retain `ZulipStore` as the in-memory state exposed to the UI. Cached state is loaded and published before network registration or refresh. Successful API responses and events update Room and memory together through repository methods.

Use a Room-backed outbox and `LightWork` for durable sending. A send is persisted before network access, attachment files are copied into app-owned durable storage, and a unique account-scoped worker retries transient failures.

An HTTP response cache is not used. Zulip responses represent mutable application state, event ordering must be preserved, and multipart uploads require durable workflow state rather than raw response reuse.

## Storage Model

The existing `ZulipDatabase` remains the database entry point and advances from schema version 1 to version 2 with an explicit migration.

All cached and outbound records are scoped by account ID. Foreign keys cascade when an account is removed.

### Cached streams

Each stream row stores:

- account ID
- stream ID
- name
- description
- invite-only status
- last-updated timestamp

The `(account_id, stream_id)` pair is unique.

### Cached users

Each user row stores:

- account ID
- user ID
- full name
- email
- active status
- last-updated timestamp

The `(account_id, user_id)` pair is unique.

### Cached topics

Each topic row stores:

- account ID
- stream ID
- topic name
- maximum message ID
- last-updated timestamp

The `(account_id, stream_id, topic_name)` tuple is unique.

### Cached conversations

A conversation row identifies a Zulip narrow with a stable encoded key matching the existing `ZulipStore` narrow key format. It stores:

- account ID
- narrow key
- display metadata needed by inbox and list screens
- unread count
- last-message ID
- last-message timestamp
- last-updated timestamp

The `(account_id, narrow_key)` pair is unique.

### Cached messages

Each message row stores:

- account ID
- narrow key
- Zulip message ID
- the serialized message fields required to reconstruct `ZulipMessage`
- received or refreshed timestamp

The `(account_id, message_id)` pair is unique. A message may be projected into the appropriate narrow using its stored narrow key.

### Pending messages

Each outbox message stores:

- generated local ID
- account ID
- encoded narrow
- original text content
- state: `QUEUED`, `SENDING`, or `FAILED`
- creation timestamp
- attempt timestamp
- permanent failure reason, when present

Rows are processed in creation order per account. Only one worker processes an account's outbox at a time.

### Pending attachments

Each pending attachment stores:

- generated local ID
- parent outbox message ID
- source ordering index
- display name
- MIME type
- durable local file path
- uploaded Zulip URL, when upload has completed

Recording the uploaded URL checkpoints progress so a retry does not upload completed attachments again.

## Cache Loading and Refresh

### Account activation

When an account becomes active:

1. Read cached streams, users, conversations, unread counts, and recent messages from Room.
2. Populate the existing `ZulipStore` maps and `StateFlow`s.
3. Publish the store as active without waiting for the network.
4. Register the Zulip event queue in the background.
5. Merge a successful register snapshot into Room and memory.
6. Keep cached state visible if registration fails.
7. Start or resume event polling after successful registration.

An account with no cached data retains the existing initial-loading experience and receives an actionable retry error if registration fails.

### Message list

Opening a conversation follows stale-while-revalidate:

1. Read up to 25 cached messages and display them immediately.
2. Mark the UI as refreshing without clearing those messages.
3. Request the newest messages from Zulip.
4. Replace the conversation's cached snapshot on success.
5. Keep cached messages visible and set connection state to offline on transient failure.

If both cache and network are empty, the normal empty conversation view is shown. If the cache is empty and the request fails, the screen shows an actionable full-screen error.

### Topics

Cached topics are displayed first and replaced by a successful server response. A transient refresh failure leaves cached topics visible.

### Event updates

Each accepted event is applied through a repository boundary that updates durable and in-memory state. Event IDs remain managed by `ZulipStore`; the queue itself is recreated after process death. A fresh registration snapshot reconciles any events missed while the application was stopped.

## Retention and Pruning

The cache retains no more than the 25 newest messages for each account and narrow. Messages older than seven days are removed even when fewer than 25 remain.

Pruning runs:

- after writing a refreshed message snapshot
- after persisting message events
- once after account initialization

Streams, users, topics, and conversation metadata are replaced or removed when authoritative register and topic responses indicate they are stale. Removing an account deletes all of its cache rows, pending messages, and durable attachment files.

## Durable Send Flow

Submitting a message no longer performs network work directly from `ComposeViewModel`.

1. Validate the account, narrow, text, and selected attachment files.
2. Copy attachments into an account-scoped outbox directory owned by the app.
3. Insert the pending message and attachment metadata in one Room transaction.
4. Clear compose state and return success to the screen once durable enqueueing succeeds.
5. Enqueue unique work named `zulip-outbox-<account-id>`.
6. Process pending messages in creation order.
7. Upload incomplete attachments sequentially and persist each returned URL.
8. Build the final message body from the original text and uploaded links.
9. Call the Zulip send-message API.
10. Delete the pending rows and durable attachment files after confirmation.

Enqueueing fails visibly if attachment copying or the Room transaction fails. It does not report that the message reached Zulip; it reports only that local durable acceptance succeeded.

## Retry and Failure Classification

DNS failures, connection failures, socket timeouts, HTTP 408, HTTP 429, and HTTP 5xx responses are transient. The job returns `LightJobResult.Retry` so WorkManager applies backoff.

Authentication failures, invalid recipients, unsupported file types, upload-size rejection, and other non-retryable HTTP 4xx responses are permanent. The pending message moves to `FAILED` with a user-readable reason and the worker continues with later rows.

Before processing, a worker resets stale `SENDING` rows to `QUEUED`; this recovers work interrupted by process death.

Completed attachment URLs remain checkpointed across attempts. If message submission itself times out after the server accepted it, the retry can create a duplicate because the current Zulip send API call has no client idempotency key. This limitation is documented in the UI copy and tests; the client does not claim exactly-once delivery.

## User Experience

Cached content is never cleared solely because a refresh fails.

Connection state is represented separately from content:

- `Online`: the latest registration or refresh succeeded
- `Refreshing`: cached content is visible while a request is active
- `Offline`: a transient connection failure occurred and cached content remains visible

The home screen shows a compact pending-message count when the count is greater than zero. Queued messages are not inserted into conversation lists before server confirmation.

Permanent outbox failures produce a dismissible error with actions to retry or discard. Retrying changes the row to `QUEUED` and schedules account work. Discarding removes the row and its attachment files.

A successful registration, refresh, or event poll clears stale connection errors automatically. API validation errors remain separate from connectivity state.

## Component Boundaries

### `ZulipCacheRepository`

Owns Room reads, writes, mapping, account isolation, transactions, and pruning. It does not call the network or mutate UI state directly.

### `ZulipOutboxRepository`

Owns durable enqueueing, attachment file lifecycle, outbox state transitions, retry, discard, and pending counts. It does not schedule work or call Zulip APIs.

### `ZulipSyncCoordinator`

Coordinates cached startup, register snapshots, message and topic refreshes, event persistence, and updates to `ZulipStore`.

### `ZulipOutboxProcessor`

Processes one account's queued rows using `ZulipClient`, classifies failures, checkpoints uploads, and removes confirmed work.

### Light job handler

A top-level `@LightJob` handler resolves the repositories and processor through `ZulipServices`, processes the account supplied in input data, and returns `Success`, `Retry`, or `Error`.

## Database Migration

Migration 1-to-2 creates the cache and outbox tables and indexes without modifying existing account rows. Existing credentials and the active account preference remain valid.

No destructive migration fallback is permitted. Migration tests create a version-1 database, apply the migration, verify existing account data, and exercise every new DAO.

## Testing

Unit and database tests cover:

- entity/model mapping for every cached type
- cache isolation between accounts
- 25-message and seven-day pruning
- cached-first account initialization
- stale-while-revalidate message and topic loading
- preserving cached data after DNS, connection, and timeout failures
- register snapshot replacement and event persistence
- durable enqueue transactions and attachment copying
- restart recovery for `QUEUED` and stale `SENDING` rows
- skipping already-uploaded attachments
- cleanup after confirmation, discard, and account removal
- transient and permanent error classification
- unique work naming per account
- retry and discard transitions
- database migration from version 1 to version 2
- ViewModel state for cached, refreshing, offline, pending, and failed cases

Network-facing classes and work scheduling are injected behind interfaces so tests use deterministic fakes. Production network access is not required by the test suite.

## Success Criteria

- Previously opened conversations display cached messages after a cold start with no server connectivity.
- Cached streams, users, topics, and inbox metadata remain usable during DNS failures and slow refreshes.
- A network refresh never clears usable cached content on failure.
- Text and attachment messages survive process death after local enqueueing.
- Transient failures retry without user action.
- Permanent failures can be retried or discarded.
- Cache data never crosses account boundaries.
- Storage remains bounded to 25 messages per conversation and seven days.
