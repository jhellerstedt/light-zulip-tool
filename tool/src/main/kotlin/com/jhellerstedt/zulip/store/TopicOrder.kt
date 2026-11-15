package com.jhellerstedt.zulip.store

import com.jhellerstedt.zulip.api.models.ZulipTopic

/** Newest topic first. Topics with the same latest message stay alphabetical. */
fun List<ZulipTopic>.byLatestActivity(): List<ZulipTopic> =
    sortedWith(
        compareByDescending<ZulipTopic> { it.maxId }
            .thenBy { it.name.lowercase() },
    )
