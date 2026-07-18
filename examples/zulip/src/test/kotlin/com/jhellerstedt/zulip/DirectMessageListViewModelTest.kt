package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.data.CachedConversationEntity
import com.jhellerstedt.zulip.data.CachedUserEntity
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.cacheKey
import com.jhellerstedt.zulip.ui.DirectMessageListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke test for the risk noted in the offline-cache plan: [DirectMessageListViewModel] must
 * reflect cached DM conversations as soon as an account is activated, without an intermediate
 * empty-list flash while the (possibly slow or failing) network register is still in flight.
 *
 * This is a regression test for a real gap: [com.jhellerstedt.zulip.store.ZulipStore.applyCachedSnapshot]
 * previously left `recentDirectMessages` empty because only [com.jhellerstedt.zulip.store.ZulipStore.applyRegisterResponse]
 * populated it, so a cached account with DM history showed an empty DM list until registration succeeded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectMessageListViewModelTest {
    private val now = 1_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cachedDirectMessagesAppearImmediatelyOnAccountActivationWithoutAnEmptyFlash() = runTest {
        val accountId = 1L
        val dmNarrow = Narrow.Dm(listOf(2))
        val dao = SeedableListCacheDao()
        dao.userRows += CachedUserEntity(
            accountId = accountId,
            userId = 2,
            fullName = "Bob Brown",
            email = "bob@example.com",
            isActive = true,
            updatedAt = now,
        )
        dao.conversationRows += CachedConversationEntity(
            accountId = accountId,
            narrowKey = dmNarrow.cacheKey(),
            title = "Bob Brown",
            unreadCount = 0,
            lastMessageId = 42,
            lastMessageTimestamp = now,
            updatedAt = now,
        )
        val cache = ZulipCacheRepository(dao, nowMillis = { now })
        val accountStore = InMemoryListAccountStore(listOf(fixtureListAccount(accountId, "a@example.com")))
        val globalStore = GlobalStore(
            accountRepository = accountStore,
            cacheRepository = cache,
            gateway = ImmediatelyFailingListGateway(),
            scope = backgroundScope,
        )
        val viewModel = DirectMessageListViewModel(globalStore)

        val emissions = mutableListOf<List<String>>()
        val collectorJob = launch(Dispatchers.Main) {
            viewModel.rows.collect { rows -> emissions.add(rows.map { it.title }) }
        }

        // Before any account is active, the list is (correctly) empty.
        assertEquals(listOf(emptyList<String>()), emissions)

        globalStore.switchAccount(accountId).getOrThrow()

        // Cache hydration must publish the cached DM conversation synchronously with account
        // activation; there must be no separate later emission that ever regresses back to empty.
        assertEquals(listOf("Bob Brown"), viewModel.rows.value.map { it.title })
        assertTrue(emissions.drop(1).none { it.isEmpty() })

        collectorJob.cancel()
    }
}
