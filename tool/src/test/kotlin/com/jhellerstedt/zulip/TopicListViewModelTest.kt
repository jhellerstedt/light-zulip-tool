package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipApiException
import com.jhellerstedt.zulip.api.models.ZulipTopic
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.ui.TopicListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TopicListViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cachedTopicsRemainVisibleWhenRefreshFails() = runTest {
        val cached = listOf(ZulipTopic("cached", 1))
        val viewModel = TopicListViewModel(
            streamId = 1,
            streamName = "General",
            cachedTopics = { cached },
            refreshTopics = { Result.failure(ZulipApiException.Network("offline")) },
        )

        viewModel.loadTopics()
        advanceUntilIdle()
        withTimeout(1_000) { viewModel.uiState.first { !it.isRefreshing } }

        assertEquals(cached, viewModel.uiState.value.topics)
        assertEquals(ConnectionState.OFFLINE, viewModel.uiState.value.connectionState)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun emptyCacheShowsErrorWhenRefreshFails() = runTest {
        val viewModel = TopicListViewModel(
            streamId = 1,
            streamName = "General",
            cachedTopics = { emptyList() },
            refreshTopics = { Result.failure(ZulipApiException.Network("offline")) },
        )

        viewModel.loadTopics()
        advanceUntilIdle()
        withTimeout(1_000) { viewModel.uiState.first { !it.isRefreshing } }

        assertEquals(emptyList(), viewModel.uiState.value.topics)
        assertEquals(ConnectionState.OFFLINE, viewModel.uiState.value.connectionState)
        assertEquals("offline", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun successfulRefreshReplacesCachedTopics() = runTest {
        val cached = listOf(ZulipTopic("cached", 1))
        val refreshed = listOf(ZulipTopic("fresh", 2))
        val viewModel = TopicListViewModel(
            streamId = 1,
            streamName = "General",
            cachedTopics = { cached },
            refreshTopics = { Result.success(refreshed) },
        )

        viewModel.loadTopics()
        advanceUntilIdle()
        withTimeout(1_000) { viewModel.uiState.first { !it.isRefreshing } }

        assertEquals(refreshed, viewModel.uiState.value.topics)
        assertEquals(ConnectionState.ONLINE, viewModel.uiState.value.connectionState)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }
}
