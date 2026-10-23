package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.data.CachedStreamEntity
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.store.GlobalStore
import com.jhellerstedt.zulip.ui.StreamListViewModel
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
 * Smoke test for the risk noted in the offline-cache plan: [StreamListViewModel] must reflect
 * hydrated cache state as soon as an account is activated, without an intermediate empty-list
 * flash while the (possibly slow or failing) network register is still in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamListViewModelTest {
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
    fun cachedStreamsAppearImmediatelyOnAccountActivationWithoutAnEmptyFlash() = runTest {
        val accountId = 1L
        val dao = SeedableListCacheDao()
        dao.streamRows += CachedStreamEntity(
            accountId = accountId,
            streamId = 10,
            name = "general",
            description = "",
            inviteOnly = false,
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
        val viewModel = StreamListViewModel(globalStore)

        val emissions = mutableListOf<List<String>>()
        val collectorJob = launch(Dispatchers.Main) {
            viewModel.streams.collect { streams -> emissions.add(streams.map { it.name }) }
        }

        // Before any account is active, the list is (correctly) empty.
        assertEquals(listOf(emptyList<String>()), emissions)

        globalStore.switchAccount(accountId).getOrThrow()

        // Cache hydration must publish the cached stream synchronously with account activation;
        // there must be no separate later emission that ever regresses back to empty.
        assertEquals(listOf("general"), viewModel.streams.value.map { it.name })
        assertTrue(emissions.drop(1).none { it.isEmpty() })

        collectorJob.cancel()
    }
}
