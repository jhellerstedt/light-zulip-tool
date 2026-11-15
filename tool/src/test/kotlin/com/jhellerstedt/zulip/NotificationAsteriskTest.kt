package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.models.ZulipTopic
import com.jhellerstedt.zulip.store.byLatestActivity
import com.jhellerstedt.zulip.ui.unreadLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationAsteriskTest {
    @Test
    fun asteriskPostsOnlyWhenEnabledUnreadAndAllowed() {
        assertEquals(
            NotificationAsteriskAction.Post,
            notificationAsteriskAction(
                enabled = true,
                anyRealmHasUnread = true,
                permissionGranted = true,
            ),
        )
        assertEquals(
            NotificationAsteriskAction.Clear,
            notificationAsteriskAction(
                enabled = false,
                anyRealmHasUnread = true,
                permissionGranted = true,
            ),
        )
        assertEquals(
            NotificationAsteriskAction.Clear,
            notificationAsteriskAction(
                enabled = true,
                anyRealmHasUnread = false,
                permissionGranted = true,
            ),
        )
        assertEquals(
            NotificationAsteriskAction.Clear,
            notificationAsteriskAction(
                enabled = true,
                anyRealmHasUnread = true,
                permissionGranted = false,
            ),
        )
    }

    @Test
    fun unreadLabelPrefixesOnlyMarkedRows() {
        assertEquals("* general", unreadLabel("general", unread = true))
        assertEquals("general", unreadLabel("general", unread = false))
    }

    @Test
    fun topicsSortByNewestMessageThenName() {
        val sorted = listOf(
            ZulipTopic("zeta", 1),
            ZulipTopic("alpha", 1),
            ZulipTopic("new", 5),
        ).byLatestActivity()

        assertEquals(listOf("new", "alpha", "zeta"), sorted.map { it.name })
    }
}
