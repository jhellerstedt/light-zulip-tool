package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.models.RecentPrivateConversation
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.ZulipStream
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.store.ZulipStore
import com.jhellerstedt.zulip.ui.ChooseParticipantsViewModel
import com.jhellerstedt.zulip.ui.selectableUsersFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ChooseParticipantsViewModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun viewModelSeedsRecentUserIdsFromStoreRecentDirectMessages() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
                ZulipUser(userId = 4, fullName = "Dana Doe", email = "dana@example.com"),
                ZulipUser(userId = 5, fullName = "Evan Evans", email = "evan@example.com"),
            ),
            recentPrivateConversations = arrayOf(
                RecentPrivateConversation(userIds = listOf(2, 3), maxMessageId = 300),
                RecentPrivateConversation(userIds = listOf(4), maxMessageId = 200),
                RecentPrivateConversation(userIds = listOf(3, 5), maxMessageId = 100),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(store)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ChooseParticipantsViewModel(
                activeStore = activeStore,
                observationScope = observerScope,
            )

            val state = withTimeout(1_000) {
                viewModel.uiState.first { it.recentUserIds == listOf(2, 3, 4, 5) }
            }

            assertEquals(listOf(2, 3, 4, 5), state.recentUserIds)
        } finally {
            observerScope.cancel()
            store.client.close()
        }
    }

    @Test
    fun viewModelKeepsStoreBackedRecentsAfterLocalParticipantPromotion() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
                ZulipUser(userId = 4, fullName = "Dana Doe", email = "dana@example.com"),
                ZulipUser(userId = 5, fullName = "Evan Evans", email = "evan@example.com"),
            ),
            recentPrivateConversations = arrayOf(
                RecentPrivateConversation(userIds = listOf(2, 3), maxMessageId = 300),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(store)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ChooseParticipantsViewModel(
                activeStore = activeStore,
                observationScope = observerScope,
            )

            withTimeout(1_000) {
                viewModel.uiState.first { it.recentUserIds == listOf(2, 3) }
            }

            viewModel.addParticipant(4)
            withTimeout(1_000) {
                viewModel.uiState.first { it.recentUserIds == listOf(4, 2, 3) }
            }

            store.applyRegisterResponse(
                RegisterQueueResponse(
                    result = "success",
                    realmUsers = listOf(
                        ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                        ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                        ZulipUser(userId = 3, fullName = "Carol Clark", email = "carol@example.com"),
                        ZulipUser(userId = 4, fullName = "Dana Doe", email = "dana@example.com"),
                        ZulipUser(userId = 5, fullName = "Evan Evans", email = "evan@example.com"),
                    ),
                    recentPrivateConversations = listOf(
                        RecentPrivateConversation(userIds = listOf(5), maxMessageId = 400),
                        RecentPrivateConversation(userIds = listOf(2, 3), maxMessageId = 300),
                    ),
                ),
            )

            val state = withTimeout(1_000) {
                viewModel.uiState.first { it.recentUserIds == listOf(4, 5, 2, 3) }
            }

            assertEquals(listOf(4, 5, 2, 3), state.recentUserIds)
        } finally {
            observerScope.cancel()
            store.client.close()
        }
    }

    @Test
    fun selectableUsersFlowUpdatesWhenActiveStoreChanges() = runBlocking {
        val activeStore = MutableStateFlow<ZulipStore?>(null)
        val firstStore = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val secondStore = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 4, fullName = "Dana Doe", email = "dana@example.com"),
            ),
        )

        try {
            assertEquals(emptyList(), selectableUsersFlow(activeStore).first().map { it.userId })

            activeStore.value = firstStore
            assertEquals(listOf(1, 2), selectableUsersFlow(activeStore).first().map { it.userId })

            activeStore.value = secondStore
            assertEquals(listOf(4), selectableUsersFlow(activeStore).first().map { it.userId })
        } finally {
            firstStore.client.close()
            secondStore.client.close()
        }
    }

    @Test
    fun selectableUsersFlowUpdatesWhenCurrentStoreUsersChange() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )

        try {
            assertEquals(listOf(1), store.selectableUsersFlow.first().map { it.userId })

            val update = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) {
                    store.selectableUsersFlow
                        .drop(1)
                        .first()
                        .map { it.userId }
                }
            }

            store.applyRegisterResponse(
                RegisterQueueResponse(
                    result = "success",
                    realmUsers = listOf(
                        ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                        ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
                        ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                    ),
                ),
            )

            assertEquals(listOf(1, 2), update.await())
        } finally {
            store.client.close()
        }
    }

    @Test
    fun selectableUsersFlowUpdatesWhenRealmUserAddEventArrives() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )

        try {
            val update = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) {
                    store.selectableUsersFlow
                        .drop(1)
                        .first()
                        .map { it.userId }
                }
            }

            store.handleEvent(
                json.decodeFromString(
                    """
                    {
                      "id": 11,
                      "type": "realm_user",
                      "op": "add",
                      "person": {
                        "user_id": 2,
                        "email": "bob@example.com",
                        "full_name": "Bob Brown",
                        "is_active": true
                      }
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(listOf(1, 2), update.await())
        } finally {
            store.client.close()
        }
    }

    @Test
    fun selectableUsersFlowUpdatesWhenRealmUserRenameEventArrives() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )

        try {
            val update = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) {
                    store.selectableUsersFlow
                        .drop(1)
                        .first()
                        .single()
                        .fullName
                }
            }

            store.handleEvent(
                json.decodeFromString(
                    """
                    {
                      "id": 12,
                      "type": "realm_user",
                      "op": "update",
                      "person": {
                        "user_id": 1,
                        "full_name": "Alicia Adams"
                      }
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals("Alicia Adams", update.await())
        } finally {
            store.client.close()
        }
    }

    @Test
    fun selectableUsersFlowUpdatesWhenRealmUserRemoveEventArrives() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
            ),
        )

        try {
            val update = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) {
                    store.selectableUsersFlow
                        .drop(1)
                        .first()
                        .map { it.userId }
                }
            }

            store.handleEvent(
                json.decodeFromString(
                    """
                    {
                      "id": 13,
                      "type": "realm_user",
                      "op": "remove",
                      "person": {
                        "user_id": 2,
                        "full_name": "Bob Brown"
                      }
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(listOf(1), update.await())
        } finally {
            store.client.close()
        }
    }

    @Test
    fun selectableUsersFlowRemovesDeactivatedRealmUser() = runBlocking {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )

        try {
            val update = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) {
                    store.selectableUsersFlow
                        .drop(1)
                        .first()
                        .map { it.userId }
                }
            }

            store.handleEvent(
                json.decodeFromString(
                    """
                    {
                      "id": 14,
                      "type": "realm_user",
                      "op": "update",
                      "person": {
                        "user_id": 1,
                        "is_active": false
                      }
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(emptyList(), update.await())
        } finally {
            store.client.close()
        }
    }

    @Test
    fun registerSnapshotReplacesUsersAndStreamsInsteadOfAppending() {
        val store = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
                ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
            ),
            streams = arrayOf(
                ZulipStream(name = "alpha", streamId = 1),
                ZulipStream(name = "beta", streamId = 2),
            ),
        )

        try {
            store.applyRegisterResponse(
                RegisterQueueResponse(
                    result = "success",
                    subscriptions = listOf(
                        ZulipStream(name = "beta", streamId = 2),
                    ),
                    realmUsers = listOf(
                        ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                        ZulipUser(userId = 2, fullName = "Bob Brown", email = "bob@example.com"),
                    ),
                ),
            )

            assertEquals(listOf(2), store.selectableUsersFlow.value.map { it.userId })
            assertEquals(listOf(2), store.streamsList.value.map { it.streamId })
        } finally {
            store.client.close()
        }
    }

    @Test
    fun viewModelResetsPickerStateWhenActiveStoreChanges() = runBlocking {
        val firstStore = storeWithUsers(
            selfUserId = 9,
            users = arrayOf(
                ZulipUser(userId = 9, fullName = "Self User", email = "self@example.com"),
                ZulipUser(userId = 1, fullName = "Alice Adams", email = "alice@example.com"),
            ),
        )
        val secondStore = storeWithUsers(
            selfUserId = 8,
            users = arrayOf(
                ZulipUser(userId = 8, fullName = "Second Self", email = "second@example.com"),
                ZulipUser(userId = 4, fullName = "Dana Doe", email = "dana@example.com"),
            ),
        )
        val activeStore = MutableStateFlow<ZulipStore?>(firstStore)
        val observerScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val viewModel = ChooseParticipantsViewModel(
                activeStore = activeStore,
                observationScope = observerScope,
            )

            withTimeout(1_000) {
                viewModel.uiState.first { it.users.map { user -> user.userId } == listOf(1) }
            }

            viewModel.activateInput()
            viewModel.updateQuery("ali")
            viewModel.addParticipant(1)
            viewModel.activateInput()
            viewModel.updateQuery("da")

            activeStore.value = secondStore

            val state = withTimeout(1_000) {
                viewModel.uiState.first {
                    it.users.map { user -> user.userId } == listOf(4) &&
                        it.selectedUserIds.isEmpty() &&
                        it.query.isEmpty() &&
                        !it.isKeyboardVisible &&
                        it.recentUserIds.isEmpty()
                }
            }

            assertEquals(listOf(4), state.users.map { it.userId })
            assertEquals(emptyList(), state.selectedUserIds)
            assertEquals("", state.query)
            assertEquals(false, state.isKeyboardVisible)
            assertEquals(emptyList(), state.recentUserIds)
        } finally {
            observerScope.cancel()
            firstStore.client.close()
            secondStore.client.close()
        }
    }

    private fun storeWithUsers(
        selfUserId: Int,
        users: Array<out ZulipUser>,
        streams: Array<out ZulipStream> = emptyArray(),
        recentPrivateConversations: Array<out RecentPrivateConversation> = emptyArray(),
    ): ZulipStore {
        val store = ZulipStore(
            account = StoredZulipAccount(
                id = 1L,
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
                subscriptions = streams.toList(),
                realmUsers = users.toList(),
                recentPrivateConversations = recentPrivateConversations.toList(),
            ),
        )
        return store
    }
}
