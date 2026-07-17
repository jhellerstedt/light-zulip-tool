# Zulip Offline Cache and Durable Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Zulip example usable through DNS failures, disconnections, and high latency by serving recent account-scoped data from Room and durably retrying text and attachment sends.

**Architecture:** Keep `ZulipStore` as the in-memory UI projection, but put Room repositories beneath it for cached server state and a durable outbox. A sync coordinator hydrates stores from Room before starting network refreshes, while an account-scoped `LightWork` job processes queued sends and checkpoints attachment uploads.

**Tech Stack:** Kotlin, Room 2.7, Ktor 3.4, coroutines and `StateFlow`, Light SDK `LightWork`, WorkManager, Jetpack Compose, `kotlin.test`, AndroidX instrumentation tests.

## Global Constraints

- Scope all cached and outbox rows by Zulip account ID.
- Retain at most 25 messages per conversation and remove messages older than seven days.
- Never clear usable cached content because a refresh failed.
- Persist a send and copy its attachments into app-owned outbox storage before reporting local acceptance.
- Keep queued messages out of conversation lists; show only a compact pending count on the home screen.
- Retry DNS, connection, timeout, HTTP 408, HTTP 429, and HTTP 5xx failures automatically.
- Require user action for permanent send failures.
- Do not use destructive Room migration fallback.
- Do not claim exactly-once delivery after an ambiguous send timeout.

---

## File Structure

### Files to create

- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipCacheEntities.kt`
  - Room entities for cached streams, users, topics, conversations, and messages.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipOutboxEntities.kt`
  - Room entities and state enum for pending messages and attachments.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipCacheDao.kt`
  - Account-scoped cache reads, replacement writes, pruning, and deletion.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipOutboxDao.kt`
  - Transactional outbox reads and state transitions.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipDatabaseMigration.kt`
  - Explicit `MIGRATION_1_2`.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipCacheRepository.kt`
  - Mapping between API models and cache rows plus retention enforcement.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipOutboxRepository.kt`
  - Durable enqueueing, attachment-file ownership, pending summaries, retry, and discard.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/api/ZulipGateway.kt`
  - Injectable API boundary shared by sync and outbox processing.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipSyncCoordinator.kt`
  - Cached hydration, register reconciliation, event persistence, and stale-while-revalidate operations.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipOutboxProcessor.kt`
  - Sequential attachment upload and message delivery state machine.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipOutboxWork.kt`
  - `LightWork` scheduler and top-level `@LightJob` handler.
- `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/NarrowCacheKeyTest.kt`
- `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipCacheRepositoryTest.kt`
- `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipSyncCoordinatorTest.kt`
- `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipOutboxRepositoryTest.kt`
- `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipOutboxProcessorTest.kt`
- `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/OutboxFailureClassifierTest.kt`
- `examples/zulip/src/androidTest/kotlin/com/jhellerstedt/zulip/ZulipDatabaseMigrationTest.kt`

### Files to modify

- `gradle/libs.versions.toml`
  - Add AndroidX instrumentation-test aliases.
- `sdk/client/src/main/kotlin/com/thelightphone/sdk/LightDb.kt`
  - Allow callers to supply explicit Room migrations.
- `examples/zulip/build.gradle.kts`
  - Configure instrumentation runner and database test dependencies.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipDatabase.kt`
  - Register version-2 entities and DAOs.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/AccountRepository.kt`
  - Remove cache/outbox data and files as part of account removal orchestration.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/Narrow.kt`
  - Add stable, reversible cache-key encoding.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipStore.kt`
  - Add cache hydration and connection-state projections; stop owning direct send/upload work.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/EventPoller.kt`
  - Route registration and events through the sync coordinator.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/GlobalStore.kt`
  - Publish cached stores before network registration and expose sync/outbox operations.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/api/ZulipApiException.kt`
  - Preserve HTTP status and network causes for retry classification.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/api/ZulipClient.kt`
  - Convert transport failures to typed network errors and include status in API errors.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ZulipServices.kt`
  - Own one database and construct repositories, coordinators, processors, and schedulers.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/MessageListViewModel.kt`
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/MessageListScreen.kt`
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/TopicListViewModel.kt`
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/TopicListScreen.kt`
  - Render cached content during refresh and use non-blocking connection status.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/ComposeViewModel.kt`
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/ComposeScreen.kt`
  - Treat durable enqueue as send success and transfer attachment ownership safely.
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/ZulipHomeViewModel.kt`
- `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/ZulipHomeScreen.kt`
  - Show connection status, pending count, and retry/discard actions for permanent failures.
- Existing focused tests for `ComposeViewModel`, register compatibility, unread snapshots, and recent DMs.

---

### Task 1: Add the Version-2 Room Schema and Explicit Migration

**Files:**
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipCacheEntities.kt`
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipOutboxEntities.kt`
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipCacheDao.kt`
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipOutboxDao.kt`
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipDatabaseMigration.kt`
- Create: `examples/zulip/src/androidTest/kotlin/com/jhellerstedt/zulip/ZulipDatabaseMigrationTest.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipDatabase.kt`
- Modify: `sdk/client/src/main/kotlin/com/thelightphone/sdk/LightDb.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ZulipServices.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `examples/zulip/build.gradle.kts`

**Interfaces:**
- Produces: `ZulipCacheDao`, `ZulipOutboxDao`, `MIGRATION_1_2`.
- Produces: `SealedLightContext.buildDatabase(dbClass, dbName, vararg migrations)`.
- Produces: `OutboxMessageState { QUEUED, SENDING, FAILED }`.
- Preserves: all existing version-1 account rows and active-account DataStore preference.

- [ ] **Step 1: Add instrumentation dependencies and write the failing migration test**

Add aliases for `androidx.test:runner:1.6.2` and `androidx.test.ext:junit:1.2.1`. Set `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` in the Zulip `defaultConfig`, then add both aliases under `androidTestImplementation`.

The migration test must:

```kotlin
@RunWith(AndroidJUnit4::class)
class ZulipDatabaseMigrationTest {
    @Test
    fun migration1To2PreservesAccountsAndCreatesCacheAndOutboxTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)
        context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    realm_url TEXT NOT NULL,
                    realm_name TEXT NOT NULL,
                    email TEXT NOT NULL,
                    encrypted_api_key BLOB NOT NULL,
                    user_id INTEGER NOT NULL,
                    zulip_feature_level INTEGER NOT NULL,
                    added_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_realm_url_email " +
                    "ON accounts (realm_url, email)",
            )
            db.execSQL(
                """
                INSERT INTO accounts
                    (id, realm_url, realm_name, email, encrypted_api_key, user_id, zulip_feature_level, added_at)
                VALUES
                    (7, 'https://zulip.example', 'Example', 'a@example.com', X'0102', 11, 481, 1000)
                """.trimIndent(),
            )
            db.version = 1
        }

        Room.databaseBuilder(context, ZulipDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .use { database ->
                database.openHelper.writableDatabase.query(
                    "SELECT realm_name FROM accounts WHERE id = 7",
                ).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("Example", it.getString(0))
                }
                listOf(
                    "cached_streams",
                    "cached_users",
                    "cached_topics",
                    "cached_conversations",
                    "cached_messages",
                    "pending_messages",
                    "pending_attachments",
                ).forEach { table ->
                    database.openHelper.writableDatabase.query(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                        arrayOf(table),
                    ).use { assertTrue(it.moveToFirst(), "missing $table") }
                }
            }
    }
}
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run:

```bash
./gradlew :examples:zulip:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.jhellerstedt.zulip.ZulipDatabaseMigrationTest
```

Expected: FAIL because the version-2 schema and `MIGRATION_1_2` do not exist.

- [ ] **Step 3: Define the entities and DAOs**

Use composite primary keys and account foreign keys for cache tables. Use UUID strings as local outbox IDs so IDs can be created before transactions.

```kotlin
@Entity(
    tableName = "cached_messages",
    primaryKeys = ["account_id", "narrow_key", "message_id"],
    foreignKeys = [
        ForeignKey(
            entity = ZulipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "narrow_key", "cached_at"]),
    ],
)
data class CachedMessageEntity(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "message_id") val messageId: Int,
    @ColumnInfo(name = "narrow_key") val narrowKey: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
)
```

Follow the same naming and foreign-key pattern for:

```kotlin
CachedStreamEntity(accountId, streamId, name, description, inviteOnly, updatedAt)
CachedUserEntity(accountId, userId, fullName, email, isActive, updatedAt)
CachedTopicEntity(accountId, streamId, topicName, maxMessageId, updatedAt)
CachedConversationEntity(
    accountId,
    narrowKey,
    title,
    unreadCount,
    lastMessageId,
    lastMessageTimestamp,
    updatedAt,
)
PendingMessageEntity(
    localId,
    accountId,
    narrowKey,
    content,
    state,
    createdAt,
    attemptAt,
    failureReason,
)
PendingAttachmentEntity(
    localId,
    messageLocalId,
    sourceIndex,
    displayName,
    mimeType,
    filePath,
    uploadedUrl,
)
```

Define DAO operations with these exact signatures:

```kotlin
@Dao
interface ZulipCacheDao {
    @Query("SELECT * FROM cached_streams WHERE account_id = :accountId ORDER BY name COLLATE NOCASE")
    suspend fun streams(accountId: Long): List<CachedStreamEntity>

    @Query("SELECT * FROM cached_users WHERE account_id = :accountId ORDER BY full_name COLLATE NOCASE")
    suspend fun users(accountId: Long): List<CachedUserEntity>

    @Query("SELECT * FROM cached_topics WHERE account_id = :accountId AND stream_id = :streamId ORDER BY max_message_id DESC")
    suspend fun topics(accountId: Long, streamId: Int): List<CachedTopicEntity>

    @Query("SELECT * FROM cached_conversations WHERE account_id = :accountId")
    suspend fun conversations(accountId: Long): List<CachedConversationEntity>

    @Query(
        "SELECT * FROM cached_messages WHERE account_id = :accountId AND narrow_key = :narrowKey " +
            "ORDER BY message_id DESC LIMIT :limit",
    )
    suspend fun messages(accountId: Long, narrowKey: String, limit: Int): List<CachedMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStreams(rows: List<CachedStreamEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsers(rows: List<CachedUserEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTopics(rows: List<CachedTopicEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(rows: List<CachedConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(rows: List<CachedMessageEntity>)

    @Query("DELETE FROM cached_messages WHERE account_id = :accountId AND cached_at < :cutoff")
    suspend fun deleteMessagesOlderThan(accountId: Long, cutoff: Long)

    @Query("DELETE FROM cached_messages WHERE account_id = :accountId AND narrow_key = :narrowKey AND message_id NOT IN (:keepIds)")
    suspend fun deleteMessagesExcept(accountId: Long, narrowKey: String, keepIds: List<Int>)
}
```

`ZulipOutboxDao` must expose transactional insertion of one message with attachments, pending/failed summary flows, oldest queued-row selection, attachment reads, and explicit `markSending`, `markQueued`, `markFailed`, `checkpointUpload`, and `deleteMessage` operations.

- [ ] **Step 4: Register database version 2 and implement the migration**

Update `ZulipDatabase` to include all eight entities, expose all three DAOs, and set `version = 2`. `MIGRATION_1_2` must create the seven new tables plus foreign-key indexes using names that exactly match the entity annotations. Do not alter or recreate `accounts`.

Extend the SDK helper without breaking existing callers:

```kotlin
fun <T : RoomDatabase> SealedLightContext.buildDatabase(
    dbClass: Class<T>,
    dbName: String?,
    vararg migrations: Migration,
): T {
    return Room.databaseBuilder(androidContext.applicationContext, dbClass, dbName)
        .addMigrations(*migrations)
        .build()
}
```

Pass `MIGRATION_1_2` from `ZulipServices` when constructing the database.

- [ ] **Step 5: Run schema verification**

Run:

```bash
./gradlew :sdk:client:testDebugUnitTest :examples:zulip:kspDebugKotlin
./gradlew :examples:zulip:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.jhellerstedt.zulip.ZulipDatabaseMigrationTest
```

Expected: both commands PASS, and Room reports no migration validation mismatch.

- [ ] **Step 6: Commit the schema**

```bash
git add gradle/libs.versions.toml sdk/client/src/main/kotlin/com/thelightphone/sdk/LightDb.kt \
  examples/zulip/build.gradle.kts examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ZulipServices.kt \
  examples/zulip/src/androidTest
git commit -m "feat: add zulip cache and outbox schema"
```

### Task 2: Implement Stable Narrow Keys and the Cache Repository

**Files:**
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/Narrow.kt`
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipCacheRepository.kt`
- Create: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/NarrowCacheKeyTest.kt`
- Create: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipCacheRepositoryTest.kt`

**Interfaces:**
- Produces: `fun Narrow.cacheKey(): String` using the existing `ZulipStore` narrow-key format.
- Produces: `fun narrowFromCacheKey(value: String): Narrow?` as the lifted inverse of that format.
- Produces: `CachedAccountSnapshot`.
- Produces: `ZulipCacheRepository.loadAccount`, `messages`, `topics`, `replaceRegisterSnapshot`, `replaceMessages`, `replaceTopics`, `applyEvent`, and `prune`.

- [ ] **Step 1: Write failing narrow-codec and repository tests**

Cover all three narrows and delimiter-containing topic names:

```kotlin
@Test
fun cacheKeysRoundTrip() {
    val narrows = listOf(
        Narrow.Channel(4),
        Narrow.Topic(4, "release: mobile"),
        Narrow.Dm(listOf(9, 2, 9)),
    )
    narrows.forEach { narrow ->
        assertEquals(narrow.normalized(), narrowFromCacheKey(narrow.cacheKey()))
    }
}
```

Repository tests must use a fake `ZulipCacheDao` and fixed clock to verify:

- account IDs are supplied to every DAO operation
- register snapshots replace stream/user/conversation metadata
- messages serialize and deserialize through `Json`
- refreshed messages are sorted oldest-to-newest for UI use
- pruning deletes rows older than seven days
- pruning retains only the 25 newest IDs for each narrow
- malformed cached message JSON is skipped instead of crashing account startup

- [ ] **Step 2: Run the tests and verify they fail**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.NarrowCacheKeyTest \
  --tests com.jhellerstedt.zulip.ZulipCacheRepositoryTest
```

Expected: FAIL with unresolved cache-key and repository symbols.

- [ ] **Step 3: Lift the existing narrow-key codec into `Narrow.kt`**

Do not invent a new encoding. The spec requires the same format already used privately in `ZulipStore`:

```kotlin
fun Narrow.cacheKey(): String = when (this) {
    is Narrow.Topic -> "stream:$streamId:$topic"
    is Narrow.Channel -> "stream:$streamId:"
    is Narrow.Dm -> "dm:${userIds.distinct().sorted().joinToString(",")}"
}

fun narrowFromCacheKey(value: String): Narrow? = when {
    value.startsWith("stream:") -> {
        val parts = value.removePrefix("stream:").split(":", limit = 2)
        val streamId = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val topic = parts.getOrNull(1).orEmpty()
        if (topic.isEmpty()) Narrow.Channel(streamId) else Narrow.Topic(streamId, topic)
    }
    value.startsWith("dm:") -> {
        val ids = value.removePrefix("dm:").split(",").mapNotNull(String::toIntOrNull).distinct().sorted()
        ids.takeIf { it.isNotEmpty() }?.let(Narrow::Dm)
    }
    else -> null
}
```

Add `Narrow.normalized()` for DM user-id ordering, then replace `ZulipStore`'s private `narrowKey`/`narrowFromKey` with these shared helpers.

- [ ] **Step 4: Implement `ZulipCacheRepository`**

Use the following public contract:

```kotlin
data class CachedAccountSnapshot(
    val streams: List<ZulipStream>,
    val users: List<ZulipUser>,
    val conversations: List<CachedConversationEntity>,
    val messagesByNarrow: Map<Narrow, List<ZulipMessage>>,
)

class ZulipCacheRepository(
    private val dao: ZulipCacheDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun loadAccount(accountId: Long): CachedAccountSnapshot
    suspend fun messages(accountId: Long, narrow: Narrow): List<ZulipMessage>
    suspend fun topics(accountId: Long, streamId: Int): List<ZulipTopic>
    suspend fun replaceRegisterSnapshot(
        accountId: Long,
        response: RegisterQueueResponse,
        conversations: List<InboxConversation>,
    )
    suspend fun replaceMessages(accountId: Long, narrow: Narrow, messages: List<ZulipMessage>)
    suspend fun replaceTopics(accountId: Long, streamId: Int, topics: List<ZulipTopic>)
    suspend fun applyEvent(accountId: Long, event: ZulipEvent, conversation: InboxConversation?)
    suspend fun prune(accountId: Long)
    suspend fun deleteAccount(accountId: Long)
}
```

Make replacement methods transactional by adding `@Transaction` DAO methods that delete stale rows and insert the authoritative replacement. Implement `prune` with `cutoff = nowMillis() - 7.days.inWholeMilliseconds`, then keep only the newest 25 IDs returned per narrow.

- [ ] **Step 5: Re-run focused and full unit tests**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.NarrowCacheKeyTest \
  --tests com.jhellerstedt.zulip.ZulipCacheRepositoryTest
./gradlew :examples:zulip:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit the cache repository**

```bash
git add examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/Narrow.kt \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipStore.kt \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipCacheDao.kt \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipCacheRepository.kt \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/NarrowCacheKeyTest.kt \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipCacheRepositoryTest.kt
git commit -m "feat: add zulip persistent cache repository"
```

### Task 3: Add the Sync Coordinator and Cached-First Account Startup

**Files:**
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/api/ZulipGateway.kt`
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipSyncCoordinator.kt`
- Create: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipSyncCoordinatorTest.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipStore.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/EventPoller.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/GlobalStore.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/ChooseAccountViewModel.kt`

**Interfaces:**
- Produces: `ConnectionState { ONLINE, REFRESHING, OFFLINE }`.
- Produces: injectable `ZulipGateway`.
- Produces: `ZulipSyncCoordinator.hydrate`, `register`, `refreshMessages`, `cachedTopics`, `refreshTopics`, and `applyEvent`.
- Changes: account switching publishes hydrated cache before starting registration.

- [ ] **Step 1: Write failing sync tests**

Use fake gateway/cache implementations and a test scope to verify:

```kotlin
@Test
fun hydratePublishesCacheBeforeRegisterCompletes() = runTest {
    val blockedRegister = CompletableDeferred<RegisterQueueResponse>()
    val store = fixtureStore()
    cache.accountSnapshot = fixtureSnapshot(messageText = "cached")
    gateway.registerResult = blockedRegister

    val hydration = coordinator.hydrate(store)
    val registerJob = launch { coordinator.register(store) }

    assertTrue(hydration.hasCachedState)
    assertEquals("cached", store.getMessagesFor(Narrow.Channel(1)).single().content)
    assertEquals(ConnectionState.REFRESHING, store.connectionState.value)
    registerJob.cancel()
}

@Test
fun failedRegisterKeepsCacheAndMarksOffline() = runTest {
    cache.accountSnapshot = fixtureSnapshot(messageText = "cached")
    gateway.registerFailure = ZulipApiException.Network("DNS failed")

    coordinator.hydrate(store)
    val result = coordinator.register(store)

    assertTrue(result.isFailure)
    assertEquals("cached", store.getMessagesFor(Narrow.Channel(1)).single().content)
    assertEquals(ConnectionState.OFFLINE, store.connectionState.value)
}
```

Also cover register snapshot persistence-before-publication, event persistence, stale queue re-registration, and successful sync clearing an offline state.

- [ ] **Step 2: Run the tests and verify they fail**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.ZulipSyncCoordinatorTest
```

Expected: FAIL because the coordinator, gateway, and connection state do not exist.

- [ ] **Step 3: Add the injectable API boundary**

```kotlin
interface ZulipGateway {
    suspend fun register(client: ZulipClient, featureLevel: Int): RegisterQueueResponse
    suspend fun events(
        client: ZulipClient,
        queueId: String,
        lastEventId: Int,
        timeoutMillis: Long,
        dontBlock: Boolean,
    ): GetEventsResponse
    suspend fun messages(client: ZulipClient, narrow: Narrow): GetMessagesResponse
    suspend fun topics(client: ZulipClient, streamId: Int): StreamTopicsResponse
    suspend fun upload(client: ZulipClient, attachment: PendingUploadAttachment): UploadFileResponse
    suspend fun send(client: ZulipClient, narrow: Narrow, content: String): SendMessageResponse
}
```

`DefaultZulipGateway` delegates to the existing functions in `ZulipRoutes.kt`. Do not duplicate endpoint construction.

- [ ] **Step 4: Add connection state and cache hydration to `ZulipStore`**

Expose:

```kotlin
enum class ConnectionState { ONLINE, REFRESHING, OFFLINE }

data class HydrationResult(val hasCachedState: Boolean)

private val _connectionState = MutableStateFlow(ConnectionState.REFRESHING)
val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

fun applyCachedSnapshot(snapshot: CachedAccountSnapshot)
fun setConnectionState(state: ConnectionState)
```

`applyCachedSnapshot` replaces maps rather than appending and calls existing publication helpers. Keep API validation errors in `_error`; do not put DNS/timeout text into a blocking modal when cache exists.

- [ ] **Step 5: Implement coordinator and rewire startup/event polling**

`hydrate` loads and prunes cache and returns whether any usable stream, user, conversation, topic, or message state exists. `register` sets `REFRESHING`, performs the feature-level compatibility retry, persists the authoritative response, applies it to memory, sets `ONLINE`, and returns `Result<Unit>`. On transient failure it sets `OFFLINE` without clearing state.

Change `GlobalStore.switchAccountLocked` to:

```kotlin
val hydration = coordinator.hydrate(store)
_activeStore.value = store
if (hydration.hasCachedState) {
    scope.launch {
        coordinator.register(store)
            .onSuccess { poller.start(store) }
    }
} else {
    coordinator.register(store).getOrThrow()
    poller.start(store)
}
return store
```

This preserves login validation and the actionable initial-load error for accounts without cache, while allowing previously synchronized accounts to open immediately offline. `ChooseAccountViewModel.initialize()` must treat cached account restore as success even when background registration fails.

Change `EventPoller` to call `gateway.events`, apply each event through `coordinator.applyEvent` so both memory and Room are updated, and call `coordinator.register` for stale queue recovery.

- [ ] **Step 6: Run sync and regression tests**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.ZulipSyncCoordinatorTest \
  --tests com.jhellerstedt.zulip.RegisterCompatibilityTest \
  --tests com.jhellerstedt.zulip.UnreadSnapshotTest \
  --tests com.jhellerstedt.zulip.RecentPrivateConversationsTest
```

Expected: PASS.

- [ ] **Step 7: Commit cached-first sync**

```bash
git add examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/api/ZulipGateway.kt \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipSyncCoordinatorTest.kt
git commit -m "feat: hydrate zulip state before network sync"
```

### Task 4: Implement Stale-While-Revalidate Messages and Topics

**Files:**
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipSyncCoordinator.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/GlobalStore.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/api/routes/ZulipRoutes.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/MessageListViewModel.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/MessageListScreen.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/TopicListViewModel.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/TopicListScreen.kt`
- Test: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/MessageListViewModelTest.kt`
- Test: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/TopicListViewModelTest.kt`

**Interfaces:**
- Produces: cached-first `GlobalStore.loadMessages(narrow)` and `loadTopics(streamId)`.
- UI state distinguishes `isRefreshing` from empty initial loading.
- Cached content survives network failure.

- [ ] **Step 1: Write failing ViewModel tests**

Add tests that inject cached reads and a controllable refresh:

```kotlin
@Test
fun cachedMessagesRemainVisibleWhenRefreshFails() = runTest {
    val cached = listOf(message(id = 1, content = "cached"))
    val viewModel = MessageListViewModel(
        narrow = Narrow.Channel(1),
        cachedMessages = { cached },
        refreshMessages = { Result.failure(ZulipApiException.Network("offline")) },
        markRead = {},
    )

    viewModel.loadMessages()
    advanceUntilIdle()

    assertEquals(cached, viewModel.uiState.value.messages)
    assertEquals(ConnectionState.OFFLINE, viewModel.uiState.value.connectionState)
    assertFalse(viewModel.uiState.value.isRefreshing)
}
```

Mirror this for topics. Add empty-cache failure tests that still set `errorMessage`, and successful refresh tests that replace cached rows.

- [ ] **Step 2: Run tests and verify they fail**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.MessageListViewModelTest \
  --tests com.jhellerstedt.zulip.TopicListViewModelTest
```

Expected: FAIL because cached and refresh dependencies are not separated.

- [ ] **Step 3: Add two-phase coordinator operations**

Implement:

```kotlin
suspend fun cachedMessages(store: ZulipStore, narrow: Narrow): List<ZulipMessage>
suspend fun refreshMessages(store: ZulipStore, narrow: Narrow): Result<List<ZulipMessage>>
suspend fun cachedTopics(store: ZulipStore, streamId: Int): List<ZulipTopic>
suspend fun refreshTopics(store: ZulipStore, streamId: Int): Result<List<ZulipTopic>>
```

Refresh methods set `REFRESHING`, persist successful responses before returning them, set `ONLINE` on success, and set `OFFLINE` only for transient transport failures. Change `getMessages` refresh calls to request `numBefore = 25` so network fetches match cache retention.

- [ ] **Step 4: Rework ViewModels and screens**

`MessageListUiState` and `TopicListUiState` gain:

```kotlin
val isRefreshing: Boolean = false
val connectionState: ConnectionState = ConnectionState.REFRESHING
```

Each `load` method publishes cache first, then refreshes. Only set a blocking `errorMessage` when cached content is empty. Replace “Loading…” text with:

```kotlin
when {
    state.connectionState == ConnectionState.OFFLINE -> "Offline — showing saved data"
    state.isRefreshing && state.messages.isNotEmpty() -> "Refreshing…"
    state.isRefreshing -> "Loading messages…"
    else -> null
}
```

Use the analogous copy for topics.

- [ ] **Step 5: Run focused and full tests**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.MessageListViewModelTest \
  --tests com.jhellerstedt.zulip.TopicListViewModelTest
./gradlew :examples:zulip:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit stale-while-revalidate UI**

```bash
git add examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/MessageList* \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/TopicList* \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/MessageListViewModelTest.kt \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/TopicListViewModelTest.kt
git commit -m "feat: show cached zulip content while refreshing"
```

### Task 5: Build the Durable Outbox Repository

**Files:**
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipOutboxRepository.kt`
- Create: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipOutboxRepositoryTest.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipOutboxDao.kt`

**Interfaces:**
- Produces: `OutboxFileStore`.
- Produces: `OutboxSummary(pendingCount, firstFailure)`.
- Produces: `enqueue`, `claimNext`, `checkpointUpload`, `requeue`, `fail`, `complete`, `retry`, `discard`, and `deleteAccount`.

- [ ] **Step 1: Write failing repository tests**

Use a temporary directory, fake DAO, deterministic IDs, and fixed clock. Cover:

- attachment files are copied before the DAO transaction
- a failed copy deletes already-created outbox files and inserts no row
- rows and files survive repository recreation
- `checkpointUpload` preserves the returned URL
- `complete`, `discard`, and account deletion remove owned files
- retry changes `FAILED` to `QUEUED`
- stale `SENDING` rows reset to `QUEUED`
- summary counts queued/sending rows and selects the oldest failure

- [ ] **Step 2: Run tests and verify they fail**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.ZulipOutboxRepositoryTest
```

Expected: FAIL with unresolved outbox repository symbols.

- [ ] **Step 3: Implement file ownership and repository state transitions**

Use these contracts:

```kotlin
interface OutboxFileStore {
    fun copyIn(accountId: Long, messageId: String, attachmentId: String, source: File): File
    fun delete(file: File)
    fun deleteAccount(accountId: Long)
}

data class OutboxSummary(
    val pendingCount: Int,
    val firstFailure: PendingMessageEntity?,
)

class ZulipOutboxRepository(
    private val dao: ZulipOutboxDao,
    private val files: OutboxFileStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(
        accountId: Long,
        narrow: Narrow,
        content: String,
        attachments: List<PendingUploadAttachment>,
    ): Result<String>
    suspend fun claimNext(accountId: Long): PendingOutboxItem?
    suspend fun checkpointUpload(attachmentId: String, url: String)
    suspend fun requeue(messageId: String)
    suspend fun fail(messageId: String, reason: String)
    suspend fun complete(item: PendingOutboxItem)
    suspend fun retry(messageId: String)
    suspend fun discard(messageId: String)
    suspend fun resetInterrupted(accountId: Long)
    suspend fun deleteAccount(accountId: Long)
    fun observeSummary(accountId: Long): Flow<OutboxSummary>
}
```

The production file store root is `<filesDir>/zulip-outbox/<account-id>/<message-id>/`. Never reuse the compose-draft path as the durable outbox path.

- [ ] **Step 4: Run tests**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.ZulipOutboxRepositoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit the outbox repository**

```bash
git add examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/ZulipOutbox* \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipOutboxRepositoryTest.kt
git commit -m "feat: add durable zulip outbox repository"
```

### Task 6: Classify Failures and Process Outbox Messages

**Files:**
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/api/ZulipApiException.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/api/ZulipClient.kt`
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipOutboxProcessor.kt`
- Create: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/OutboxFailureClassifierTest.kt`
- Create: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipOutboxProcessorTest.kt`

**Interfaces:**
- Changes: `ApiError` includes `statusCode`.
- Changes: `Network` retains its cause.
- Produces: `OutboxFailureKind`.
- Produces: `OutboxRunResult`.

- [ ] **Step 1: Write failing classifier and processor tests**

Classifier cases:

```kotlin
@Test
fun retryableFailures() {
    listOf(
        ZulipApiException.Network("DNS", UnknownHostException()),
        ZulipApiException.ApiError(408, null, "timeout"),
        ZulipApiException.ApiError(429, null, "rate limited"),
        ZulipApiException.ApiError(503, null, "unavailable"),
    ).forEach { assertEquals(OutboxFailureKind.TRANSIENT, classifyOutboxFailure(it)) }
}

@Test
fun permanentFailures() {
    listOf(
        ZulipApiException.ApiError(401, null, "unauthorized"),
        ZulipApiException.ApiError(413, null, "too large"),
        ZulipApiException.ApiError(422, null, "bad recipient"),
    ).forEach { assertEquals(OutboxFailureKind.PERMANENT, classifyOutboxFailure(it)) }
}
```

Processor tests must prove sequential upload order, checkpoint reuse after retry, final Markdown construction, successful cleanup, transient requeue with `Retry`, permanent failure continuation to later rows, and ambiguous send timeout requeue.

- [ ] **Step 2: Run tests and verify they fail**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.OutboxFailureClassifierTest \
  --tests com.jhellerstedt.zulip.ZulipOutboxProcessorTest
```

Expected: FAIL because typed status errors and processor do not exist.

- [ ] **Step 3: Preserve transport and HTTP error information**

Use:

```kotlin
sealed class ZulipApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ApiError(
        val statusCode: Int,
        val code: String?,
        message: String,
    ) : ZulipApiException(message)

    class MalformedResponse(body: String) : ZulipApiException("Malformed response: $body")
    class Network(message: String, cause: Throwable) : ZulipApiException(message, cause)
}
```

In `ZulipClient`, preserve `CancellationException`, wrap `IOException`, `HttpRequestTimeoutException`, and socket timeout failures as `Network`, and pass `statusCode` into `ApiError`. Update existing compatibility tests for the new constructor.

- [ ] **Step 4: Implement classification and processing**

```kotlin
enum class OutboxFailureKind { TRANSIENT, PERMANENT }
sealed interface OutboxRunResult {
    data object Success : OutboxRunResult
    data object Retry : OutboxRunResult
}

fun classifyOutboxFailure(error: Throwable): OutboxFailureKind = when (error) {
    is ZulipApiException.Network -> OutboxFailureKind.TRANSIENT
    is ZulipApiException.ApiError ->
        if (error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500) {
            OutboxFailureKind.TRANSIENT
        } else {
            OutboxFailureKind.PERMANENT
        }
    else -> OutboxFailureKind.PERMANENT
}
```

`ZulipOutboxProcessor.process(accountId)` resets interrupted rows, loops in creation order, skips attachments with `uploadedUrl`, checkpoints each upload before moving on, and deletes a row only after `gateway.send` returns success. A transient error requeues the current row and returns `Retry`; a permanent error records the reason and continues to the next queued row.

- [ ] **Step 5: Run focused and full tests**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.OutboxFailureClassifierTest \
  --tests com.jhellerstedt.zulip.ZulipOutboxProcessorTest \
  --tests com.jhellerstedt.zulip.RegisterCompatibilityTest
./gradlew :examples:zulip:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit processing**

```bash
git add examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/api \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipOutboxProcessor.kt \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/OutboxFailureClassifierTest.kt \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipOutboxProcessorTest.kt \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/RegisterCompatibilityTest.kt
git commit -m "feat: process zulip outbox with retry classification"
```

### Task 7: Register Account-Scoped LightWork and Wire Services

**Files:**
- Create: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipOutboxWork.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ZulipServices.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/GlobalStore.kt`
- Test: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipOutboxWorkTest.kt`

**Interfaces:**
- Produces: top-level `@LightJob("zulip-outbox")`.
- Produces: unique work name `zulip-outbox-<account-id>`.
- Produces: `OutboxScheduler.enqueue(accountId)`.
- Wires one database instance into all repositories.

- [ ] **Step 1: Write failing scheduler/handler tests**

Extract and test pure helpers:

```kotlin
@Test
fun workNameIsAccountScoped() {
    assertEquals("zulip-outbox-42", outboxWorkName(42))
}

@Test
fun handlerRejectsMissingAccountId() = runTest {
    val result = runOutboxJob(emptyMap(), processor = fakeProcessor)
    assertIs<LightJobResult.Error>(result)
}
```

Also map `OutboxRunResult.Success` to `LightJobResult.Success` and `Retry` to `LightJobResult.Retry`.

- [ ] **Step 2: Run tests and verify they fail**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.ZulipOutboxWorkTest
```

Expected: FAIL because work helpers are absent.

- [ ] **Step 3: Implement scheduler and top-level job**

```kotlin
private const val OUTBOX_JOB_KEY = "zulip-outbox"
private const val ACCOUNT_ID_INPUT = "account_id"

fun outboxWorkName(accountId: Long): String = "$OUTBOX_JOB_KEY-$accountId"

interface OutboxScheduler {
    fun enqueue(accountId: Long): Boolean
    fun cancel(accountId: Long)
}

class LightOutboxScheduler(
    private val context: SealedLightContext,
) : OutboxScheduler {
    override fun enqueue(accountId: Long): Boolean = LightWork.enqueue(
        lightContext = context,
        jobKey = OUTBOX_JOB_KEY,
        inputData = mapOf(ACCOUNT_ID_INPUT to accountId.toString()),
        tag = outboxWorkName(accountId),
    )

    override fun cancel(accountId: Long) {
        LightWork.cancel(context, outboxWorkName(accountId))
    }
}

@LightJob(OUTBOX_JOB_KEY)
val zulipOutboxJob: LightJobHandler = { context, input ->
    runOutboxJob(
        input = input,
        processor = ZulipServices.outboxProcessor(context),
    )
}
```

Keep the annotated property top-level and typed explicitly as `LightJobHandler` so KSP registers it. `LightWork.enqueue` uses `ExistingWorkPolicy.REPLACE`, so a re-enqueue can cancel in-flight work; rely on `SENDING -> QUEUED` recovery and attachment URL checkpoints rather than assuming a single uninterrupted run.

- [ ] **Step 4: Refactor `ZulipServices` around one database**

Add synchronized singleton providers for:

```kotlin
database(context)
accountRepository(context)
cacheRepository(context)
outboxRepository(context)
gateway()
syncCoordinator(context)
outboxProcessor(context)
outboxScheduler(context)
globalStore(context)
```

Every provider must reuse `database(context)`; do not construct multiple Room instances for the same file. `GlobalStore` receives the cache, sync, outbox, and scheduler dependencies explicitly.

- [ ] **Step 5: Run KSP and work tests**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.ZulipOutboxWorkTest
./gradlew :examples:zulip:kspDebugKotlin :examples:zulip:assembleDebug
```

Expected: PASS; generated `LightSdkRegistry.jobs` includes `"zulip-outbox"`.

- [ ] **Step 6: Commit work integration**

```bash
git add examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ZulipServices.kt \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/GlobalStore.kt \
  examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/ZulipOutboxWork.kt \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipOutboxWorkTest.kt
git commit -m "feat: schedule zulip outbox with LightWork"
```

### Task 8: Enqueue Compose Sends and Surface Outbox Status

**Files:**
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/store/GlobalStore.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/data/AccountRepository.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/ComposeViewModel.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/ComposeScreen.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/ZulipHomeViewModel.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/ZulipHomeScreen.kt`
- Modify: `examples/zulip/src/main/kotlin/com/jhellerstedt/zulip/ui/SettingsViewModel.kt`
- Test: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ComposeViewModelTest.kt`
- Test: `examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipHomeViewModelTest.kt`

**Interfaces:**
- Changes: `GlobalStore.enqueueMessage` durably accepts sends.
- Home UI observes pending and permanent-failure state.
- Account removal cancels work and deletes cache/outbox rows and files.

- [ ] **Step 1: Update failing Compose tests**

Replace direct-send expectations with durable enqueue expectations:

```kotlin
@Test
fun sendReportsSuccessAfterDurableEnqueue() = runTest {
    var enqueued = false
    val viewModel = composeViewModel(
        enqueueMessage = { accountId, narrow, content, attachments ->
            enqueued = true
            assertEquals(1L, accountId)
            assertEquals("hello", content)
            Result.success("local-message-id")
        },
    )

    viewModel.sendMessage("hello")
    advanceUntilIdle()

    assertTrue(enqueued)
    assertTrue(viewModel.uiState.value.sent)
}
```

Add tests that failed local persistence leaves compose open, successful enqueue transfers attachment ownership, pending count follows active account, retry schedules work, and discard removes the failure.

- [ ] **Step 2: Run UI-state tests and verify they fail**

```bash
./gradlew :examples:zulip:testDebugUnitTest \
  --tests com.jhellerstedt.zulip.ComposeViewModelTest \
  --tests com.jhellerstedt.zulip.ZulipHomeViewModelTest
```

Expected: FAIL because compose still sends directly and home has no outbox state.

- [ ] **Step 3: Route compose through durable enqueue**

Add to `GlobalStore`:

```kotlin
suspend fun enqueueMessage(
    accountId: Long,
    narrow: Narrow,
    content: String,
    attachments: List<PendingUploadAttachment>,
): Result<String> {
    val result = outboxRepository.enqueue(accountId, narrow, content, attachments)
    return result.mapCatching { localId ->
        check(outboxScheduler.enqueue(accountId)) { "Zulip outbox job is not registered" }
        localId
    }
}
```

When an account is activated, schedule its account worker whenever the outbox summary has pending rows. This recovers the narrow case where local persistence succeeded but process death occurred before WorkManager enqueueing.

Inject this operation into `ComposeViewModel`, preserve the account-switch guard, and set `sent = true` after local enqueue. Rename `isSending` copy to “Saving…”/“Queueing…” rather than “Uploading…”.

After success, clear ViewModel attachments before `goBack()` so `ComposeScreen.onScreenDestroy()` does not delete files that the repository now owns. The repository copied files, so deleting original compose drafts after transfer is safe.

- [ ] **Step 4: Add pending/failure home state and actions**

Extend `ZulipHomeUiState`:

```kotlin
val connectionState: ConnectionState = ConnectionState.REFRESHING
val pendingMessageCount: Int = 0
val failedMessage: PendingMessageEntity? = null
```

Show compact text:

```kotlin
if (state.pendingMessageCount > 0) {
    LightText(
        text = "${state.pendingMessageCount} message(s) pending",
        variant = LightTextVariant.Detail,
    )
}
```

For `failedMessage`, render an app-local full-screen overlay with bottom-bar actions `RETRY`, `DISCARD`, and `CLOSE`; the existing one-action `LightFullscreenModal` is insufficient. Retry calls `outboxRepository.retry` then schedules the account worker. Discard calls `outboxRepository.discard`.

- [ ] **Step 5: Make account removal complete**

Before deleting the account row:

```kotlin
outboxScheduler.cancel(accountId)
outboxRepository.deleteAccount(accountId)
cacheRepository.deleteAccount(accountId)
accountRepository.deleteAccount(accountId)
```

Put this orchestration in `GlobalStore.removeAccount`; keep `AccountRepository` responsible only for account-table access. Verify physical outbox files are removed even if Room foreign-key cascade already removed metadata.

- [ ] **Step 6: Run all Zulip tests and build**

```bash
./gradlew :examples:zulip:testDebugUnitTest
./gradlew :examples:zulip:assembleDebug
```

Expected: PASS and `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit UI integration**

```bash
git add examples/zulip/src/main/kotlin/com/jhellerstedt/zulip \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ComposeViewModelTest.kt \
  examples/zulip/src/test/kotlin/com/jhellerstedt/zulip/ZulipHomeViewModelTest.kt
git commit -m "feat: queue zulip sends and surface outbox status"
```

### Task 9: Verify Migration, Offline Reads, and Durable Retry End-to-End

**Files:**
- Verify: all files changed in Tasks 1–8.
- Update if needed: `docs/superpowers/specs/2026-07-17-zulip-offline-cache-design.md`

**Interfaces:**
- Verifies all success criteria without introducing new architecture.

- [ ] **Step 1: Run automated verification**

```bash
./gradlew :sdk:client:testDebugUnitTest
./gradlew :examples:zulip:testDebugUnitTest
./gradlew :examples:zulip:connectedDebugAndroidTest
./gradlew :examples:zulip:assembleDebug
```

Expected: all commands PASS.

- [ ] **Step 2: Verify cached cold start**

```text
Connect emulator to the self-hosted Zulip server.
Open a stream/topic and confirm messages load.
Close the Zulip tool.
Disconnect the emulator from the server network or disable its DNS route.
Reopen the Zulip tool.
Open the same conversation.
```

Expected: cached streams/topics and up to 25 cached messages appear; the UI says it is offline and does not replace content with the hostname-resolution modal.

- [ ] **Step 3: Verify high-latency refresh**

```text
Reconnect the server through a throttled/high-latency link.
Open a previously cached conversation.
Wait for refresh to complete.
```

Expected: cached messages appear immediately, “Refreshing…” is non-blocking, and the successful response replaces the cached snapshot.

- [ ] **Step 4: Verify durable text and attachment retry**

```text
Disconnect the emulator from the server.
Compose one text message and one image message.
Return home and confirm the pending count is 2.
Force-stop and restart the tool.
Reconnect the server.
Wait for LightWork to run.
```

Expected: both messages survive restart and send in order; the attachment uploads before its message, completed upload checkpoints are not repeated, and pending count returns to zero.

- [ ] **Step 5: Verify permanent failure controls**

Use a server upload limit below the selected image size.

Expected: the item becomes permanently failed, home offers retry/discard/close, retry requeues it, discard removes its row and durable file, and later queued messages are not blocked.

- [ ] **Step 6: Inspect final repository state**

```bash
git status --short
git diff --check
```

Expected: no whitespace errors and only intended changes remain.

- [ ] **Step 7: Commit verification fixes, if any**

If verification required code or documentation changes:

```bash
git add examples/zulip sdk/client/src/main/kotlin/com/thelightphone/sdk/LightDb.kt \
  gradle/libs.versions.toml docs/superpowers/specs/2026-07-17-zulip-offline-cache-design.md
git commit -m "test: verify zulip offline cache and outbox"
```

If no files changed, do not create an empty commit.

---

## Implementation Risks

- `buildDatabase` must gain migration support before schema work can ship; tools cannot call `Room.databaseBuilder` directly because `android.content.Context` is blocked outside the SDK helper.
- `LightWork` unique work always uses `REPLACE`, so outbox processing must recover safely from cancellation.
- `ZulipClient` currently drops HTTP status codes; failure classification depends on the Task 6 exception changes.
- `LightFullscreenModal` is dismiss-only, so permanent outbox failures need a local retry/discard overlay.
- `StreamListViewModel` and `DirectMessageListViewModel` should be smoke-tested in Task 9 to ensure cache hydration does not introduce empty flashes.

## Self-Review

### Spec coverage

- Persistent account-scoped streams, users, topics, conversations, messages: Tasks 1–4.
- Cached-first cold start and stale-while-revalidate: Tasks 3–4.
- 25-message/seven-day retention: Task 2.
- Durable text and attachment outbox: Tasks 1, 5, 6, and 8.
- Background retry through account-scoped `LightWork`: Task 7.
- Upload checkpointing and cleanup: Tasks 5–6.
- Transient/permanent failure handling: Tasks 6 and 8.
- Non-blocking offline status and pending count: Tasks 4 and 8.
- Retry/discard permanent failures: Task 8.
- Explicit non-destructive migration: Task 1.
- Process-death and end-to-end behavior: Task 9.

### Placeholder scan

No `TODO`, `TBD`, “implement later,” or “similar to another task” placeholders remain. Every task names exact files, interfaces, focused tests, commands, and expected outcomes.

### Type consistency

The plan consistently uses:

- `ConnectionState`
- `CachedAccountSnapshot`
- `ZulipCacheRepository`
- `ZulipOutboxRepository`
- `ZulipSyncCoordinator`
- `ZulipGateway`
- `OutboxMessageState`
- `OutboxSummary`
- `OutboxFailureKind`
- `OutboxRunResult`
- `OutboxScheduler`
- `outboxWorkName`

No later task introduces alternate names for these concepts.
