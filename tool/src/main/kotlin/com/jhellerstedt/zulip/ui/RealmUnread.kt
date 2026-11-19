package com.jhellerstedt.zulip.ui

import com.jhellerstedt.zulip.store.hasDirectUnread
import com.jhellerstedt.zulip.store.hasStreamUnread

/**
 * A realm row shows an asterisk when the stream/DM summary for that realm would.
 * The open realm follows the live counts that summary reads. Every other realm
 * follows the unread counts last saved for it.
 */
fun realmHasUnread(
    accountId: Long,
    activeAccountId: Long?,
    activeUnreadCounts: Map<String, Int>,
    cachedUnreadAccountIds: Set<Long>,
): Boolean = if (accountId == activeAccountId) {
    activeUnreadCounts.hasStreamUnread() || activeUnreadCounts.hasDirectUnread()
} else {
    accountId in cachedUnreadAccountIds
}
