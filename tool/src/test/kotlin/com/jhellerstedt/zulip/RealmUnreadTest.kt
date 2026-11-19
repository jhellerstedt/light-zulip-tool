package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.ui.realmHasUnread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealmUnreadTest {
    @Test
    fun activeRealmUsesTheSameUnreadTheSummaryScreenShows() {
        assertTrue(
            realmHasUnread(
                accountId = 1L,
                activeAccountId = 1L,
                activeUnreadCounts = mapOf("stream:4:general" to 2),
                cachedUnreadAccountIds = emptySet(),
            ),
        )
        assertTrue(
            realmHasUnread(
                accountId = 1L,
                activeAccountId = 1L,
                activeUnreadCounts = mapOf("dm:3" to 1),
                cachedUnreadAccountIds = emptySet(),
            ),
        )
        assertFalse(
            realmHasUnread(
                accountId = 1L,
                activeAccountId = 1L,
                activeUnreadCounts = emptyMap(),
                cachedUnreadAccountIds = setOf(1L),
            ),
        )
    }

    @Test
    fun inactiveRealmUsesCachedUnread() {
        assertTrue(
            realmHasUnread(
                accountId = 2L,
                activeAccountId = 1L,
                activeUnreadCounts = emptyMap(),
                cachedUnreadAccountIds = setOf(2L),
            ),
        )
        assertFalse(
            realmHasUnread(
                accountId = 2L,
                activeAccountId = 1L,
                activeUnreadCounts = mapOf("stream:4:general" to 2),
                cachedUnreadAccountIds = emptySet(),
            ),
        )
    }
}
