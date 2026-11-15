package com.jhellerstedt.zulip.ui

fun unreadLabel(label: String, unread: Boolean): String =
    if (unread) "* $label" else label
