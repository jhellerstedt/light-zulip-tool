package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.store.Narrow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NarrowSerializationTest {
    @Test
    fun channelNarrowUsesNumericOperand() {
        val json = Narrow.Channel(59).toJson()

        assertTrue(json.contains("\"operand\":59"))
        assertFalse(json.contains("\"operand\":\"59\""))
    }

    @Test
    fun topicNarrowUsesNumericStreamOperand() {
        val json = Narrow.Topic(59, "general").toJson()

        assertTrue(json.contains("\"operand\":59"))
        assertFalse(json.contains("\"operand\":\"59\""))
    }

    @Test
    fun dmNarrowUsesSerializableJsonArrayOperand() {
        val json = Narrow.Dm(listOf(42, 73)).toJson()

        assertTrue(json.contains("\"operand\":[42,73]"))
    }

    @Test
    fun dmDisplayTitleFallsBackToUserIdsWhenNamesMissing() {
        val title = Narrow.Dm(listOf(42, 73)).displayTitle(emptyMap(), emptyMap())

        assertEquals("User 42, User 73", title)
    }
}
