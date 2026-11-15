package com.jhellerstedt.zulip

enum class NotificationAsteriskAction { Post, Clear }

enum class NotificationPermission { Granted, Denied, Unavailable }

/**
 * LightOS shows a dead-end "Not allowed" screen when a tool asks for a permission
 * the phone will not grant. Only a real denial is worth opening that prompt.
 */
fun launchNotificationPermissionPrompt(permission: NotificationPermission): Boolean =
    permission == NotificationPermission.Denied

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
