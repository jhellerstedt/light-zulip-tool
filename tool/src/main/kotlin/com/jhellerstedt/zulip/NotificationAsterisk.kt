package com.jhellerstedt.zulip

enum class NotificationAsteriskAction { Post, Clear }

/**
 * LightOS draws an asterisk beside the tool while a notification is posted.
 * The mark is cleared by cancelling that notification.
 */
fun notificationAsteriskAction(
    enabled: Boolean,
    anyRealmHasUnread: Boolean,
    permissionGranted: Boolean,
): NotificationAsteriskAction =
    if (enabled && anyRealmHasUnread && permissionGranted) {
        NotificationAsteriskAction.Post
    } else {
        NotificationAsteriskAction.Clear
    }
