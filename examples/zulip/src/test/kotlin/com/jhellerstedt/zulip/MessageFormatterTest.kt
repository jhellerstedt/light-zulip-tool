package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.util.stripHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageFormatterTest {
    @Test
    fun stripHtmlRemovesTags() {
        assertEquals(
            "Hello world",
            stripHtml("<p>Hello <b>world</b></p>"),
        )
    }

    @Test
    fun stripHtmlDecodesEntities() {
        assertTrue(stripHtml("a &amp; b").contains("&"))
    }
}
