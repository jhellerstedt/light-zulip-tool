package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipApiException
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.store.ConnectionState
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.ui.MessageListViewModel
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
class MessageListViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
        withTimeout(1_000) { viewModel.uiState.first { !it.isRefreshing } }

        assertEquals(cached, viewModel.uiState.value.messages)
        assertEquals(ConnectionState.OFFLINE, viewModel.uiState.value.connectionState)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun emptyCacheShowsErrorWhenRefreshFails() = runTest {
        val viewModel = MessageListViewModel(
            narrow = Narrow.Channel(1),
            cachedMessages = { emptyList() },
            refreshMessages = { Result.failure(ZulipApiException.Network("offline")) },
            markRead = {},
        )

        viewModel.loadMessages()
        advanceUntilIdle()
        withTimeout(1_000) { viewModel.uiState.first { !it.isRefreshing } }

        assertEquals(emptyList(), viewModel.uiState.value.messages)
        assertEquals(ConnectionState.OFFLINE, viewModel.uiState.value.connectionState)
        assertEquals("offline", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun successfulRefreshReplacesCachedMessages() = runTest {
        val cached = listOf(message(id = 1, content = "cached"))
        val refreshed = listOf(message(id = 2, content = "fresh"))
        val viewModel = MessageListViewModel(
            narrow = Narrow.Channel(1),
            cachedMessages = { cached },
            refreshMessages = { Result.success(refreshed) },
            markRead = {},
        )

        viewModel.loadMessages()
        advanceUntilIdle()
        withTimeout(1_000) { viewModel.uiState.first { !it.isRefreshing } }

        assertEquals(refreshed, viewModel.uiState.value.messages)
        assertEquals(ConnectionState.ONLINE, viewModel.uiState.value.connectionState)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    private fun message(id: Int, content: String) = ZulipMessage(
        id = id,
        type = "stream",
        content = content,
        streamId = 1,
    )
}
