package com.thelightphone.zulip

import com.thelightphone.zulip.api.ZulipClient
import com.thelightphone.zulip.api.models.RegisterQueueResponse
import com.thelightphone.zulip.api.models.ZulipUser
import com.thelightphone.zulip.data.StoredZulipAccount
import com.thelightphone.zulip.store.Narrow
import com.thelightphone.zulip.store.ZulipStore
import com.thelightphone.zulip.ui.ComposeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeViewModelTest {
    @Test
    fun uiStateSeedsMentionableUsersFromActiveStore() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(store)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(1)),
                observationScope = observerScope,
            )

            val state = withTimeout(1_000) {
                viewModel.uiState.first { uiState ->
                    uiState.mentionableUsers.map { user -> user.userId } == listOf(1, 2)
                }
            }

            assertEquals(listOf(1, 2), state.mentionableUsers.map { it.userId })
        } finally {
            observerScope.cancel()
            store.client.close()
        }
    }

    @Test
    fun uiStateUpdatesMentionableUsersWhenStoreChanges() = runBlocking {
        val firstStore = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val secondStore = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(firstStore)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(2)),
                observationScope = observerScope,
            )

            withTimeout(1_000) {
                viewModel.uiState.first { uiState ->
                    uiState.mentionableUsers.map { user -> user.userId } == listOf(1)
                }
            }

            activeStore.value = secondStore

            val state = withTimeout(1_000) {
                viewModel.uiState.first { uiState ->
                    uiState.mentionableUsers.map { user -> user.userId } == listOf(2, 3)
                }
            }

            assertEquals(listOf(2, 3), state.mentionableUsers.map { it.userId })
        } finally {
            observerScope.cancel()
            firstStore.client.close()
            secondStore.client.close()
        }
    }

    @Test
    fun uiStateUpdatesMentionableUsersWhenSameStoreChanges() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(store)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(1)),
                observationScope = observerScope,
            )

            withTimeout(1_000) {
                viewModel.uiState.first { uiState ->
                    uiState.mentionableUsers.map { user -> user.userId } == listOf(1)
                }
            }

            store.applyRegisterResponse(
                RegisterQueueResponse(
                    result = "success",
                    realmUsers = listOf(
                        ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                        ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                        ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
                    ),
                ),
            )

            val state = withTimeout(1_000) {
                viewModel.uiState.first { uiState ->
                    uiState.mentionableUsers.map { user -> user.userId } == listOf(2, 3)
                }
            }

            assertEquals(listOf(2, 3), state.mentionableUsers.map { it.userId })
        } finally {
            observerScope.cancel()
            store.client.close()
        }
    }

    @Test
    fun uiStateUpdatesTitleWhenActiveStoreChanges() = runBlocking {
        val firstStore = storeWithUsers(
            accountId = 1L,
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val secondStore = storeWithUsers(
            accountId = 2L,
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alicia Adams", email = "alice@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(firstStore)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(1)),
                observationScope = observerScope,
            )

            withTimeout(1_000) {
                viewModel.uiState.first { it.title == "Compose: Alice Adams" }
            }

            activeStore.value = secondStore

            val state = withTimeout(1_000) {
                viewModel.uiState.first { it.title == "Compose: Alicia Adams" }
            }

            assertEquals("Compose: Alicia Adams", state.title)
        } finally {
            observerScope.cancel()
            firstStore.client.close()
            secondStore.client.close()
        }
    }

    @Test
    fun uiStateUpdatesMentionTokenAccountIdWhenActiveStoreChanges() = runBlocking {
        val firstStore = storeWithUsers(
            accountId = 11L,
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val secondStore = storeWithUsers(
            accountId = 22L,
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(firstStore)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(1)),
                observationScope = observerScope,
            )

            withTimeout(1_000) {
                viewModel.uiState.first { it.mentionTokenAccountId == 11L }
            }

            activeStore.value = secondStore

            val state = withTimeout(1_000) {
                viewModel.uiState.first { it.mentionTokenAccountId == 22L }
            }

            assertEquals(22L, state.mentionTokenAccountId)
        } finally {
            observerScope.cancel()
            firstStore.client.close()
            secondStore.client.close()
        }
    }

    @Test
    fun uiStateUpdatesMentionTokenAccountAndUsersAtomicallyWhenStoreChanges() = runBlocking {
        val firstStore = storeWithUsers(
            accountId = 11L,
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val secondStore = storeWithUsers(
            accountId = 22L,
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(firstStore)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val observedStates = mutableListOf<List<Any?>>()

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(1)),
                observationScope = observerScope,
            )
            val collector = observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                viewModel.uiState.collect { state ->
                    observedStates += listOf(
                        state.mentionTokenAccountId,
                        state.mentionableUsers.map { it.userId },
                    )
                }
            }

            withTimeout(1_000) {
                viewModel.uiState.first {
                    it.mentionTokenAccountId == 11L &&
                        it.mentionableUsers.map { user -> user.userId } == listOf(1)
                }
            }

            val switchStartIndex = observedStates.size
            activeStore.value = secondStore

            withTimeout(1_000) {
                viewModel.uiState.first {
                    it.mentionTokenAccountId == 22L &&
                        it.mentionableUsers.map { user -> user.userId } == listOf(2, 3)
                }
            }

            val statesAfterSwitch = observedStates.drop(switchStartIndex)
            assertTrue(
                statesAfterSwitch.none { (accountId, userIds) ->
                    accountId == 22L && userIds != listOf(2, 3)
                },
            )

            collector.cancel()
        } finally {
            observerScope.cancel()
            firstStore.client.close()
            secondStore.client.close()
        }
    }

    @Test
    fun sendMessageUsesCurrentActiveStoreValue() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(store)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(1)),
                observationScope = observerScope,
            )

            activeStore.value = null
            viewModel.sendMessage("hello")

            val state = withTimeout(1_000) {
                viewModel.uiState.first { it.errorMessage == "No active account" }
            }

            assertEquals("No active account", state.errorMessage)
        } finally {
            observerScope.cancel()
            store.client.close()
        }
    }

    @Test
    fun sendMessageBlocksWhenActiveStoreSwitchesToDifferentAccount() = runBlocking {
        val firstStore = storeWithUsers(
            accountId = 1L,
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val secondStore = storeWithUsers(
            accountId = 2L,
            selfUserId = 8,
            users = arrayOf(
                ZulipUser(userId = 8, fullName = "Second Self", email = "second@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(firstStore)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())
        var sendCalls = 0

        try {
            val viewModel = ComposeViewModel(
                activeStore = activeStore,
                narrow = Narrow.Dm(listOf(2)),
                observationScope = observerScope,
                sendMessageWithStore = { store, narrow, content, attachments ->
                    sendCalls++
                    assertEquals(Narrow.Dm(listOf(2)), narrow)
                    assertEquals("hello", content)
                    assertEquals(emptyList(), attachments)
                    assertEquals(secondStore, store)
                    Result.success(123)
                },
            )

            activeStore.value = secondStore
            viewModel.sendMessage("hello")

            val state = withTimeout(1_000) {
                viewModel.uiState.first { it.errorMessage == "Active account changed. Reopen compose to send." }
            }

            assertEquals("Active account changed. Reopen compose to send.", state.errorMessage)
            assertEquals(0, sendCalls)
        } finally {
            observerScope.cancel()
            firstStore.client.close()
            secondStore.client.close()
        }
    }

    private fun storeWithUsers(
        accountId: Long = 1L,
        selfUserId: Int,
        users: Array<out ZulipUser>,
    ): ZulipStore {
        val store = ZulipStore(
            account = StoredZulipAccount(
                id = accountId,
                realmUrl = "https://example.com",
                realmName = "Example",
                email = "self@example.com",
                userId = selfUserId,
                zulipFeatureLevel = 0,
            ),
            client = ZulipClient("https://example.com", "self@example.com", "api-key"),
        )
        store.applyRegisterResponse(
            RegisterQueueResponse(
                result = "success",
                realmUsers = users.toList(),
            ),
        )
        return store
    }
}
