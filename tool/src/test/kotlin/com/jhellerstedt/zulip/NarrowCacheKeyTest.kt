package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.cacheKey
import com.jhellerstedt.zulip.store.narrowFromCacheKey
import com.jhellerstedt.zulip.store.normalized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NarrowCacheKeyTest {
    @Test
    fun cacheKeysRoundTrip() {
        val narrows = listOf(
            Narrow.Channel(4),
            Narrow.Topic(4, "release: mobile"),
            Narrow.Dm(listOf(9, 2, 9)),
        )

        narrows.forEach { narrow ->
            assertEquals(narrow.normalized(), narrowFromCacheKey(narrow.cacheKey()))
        }
    }

    @Test
    fun malformedCacheKeysAreRejected() {
        listOf("", "stream:", "stream:not-an-id:topic", "dm:", "other:1").forEach { key ->
            assertNull(narrowFromCacheKey(key))
        }
    }
}
