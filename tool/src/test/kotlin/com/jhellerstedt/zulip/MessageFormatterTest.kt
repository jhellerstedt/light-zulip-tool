package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.util.presentMessageContent
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

    @Test
    fun presentMessageContentNamesPhotosAndFiles() {
        assertEquals(
            "Hello Photo: pic.png",
            presentMessageContent("Hello\n\n[pic.png](/user_uploads/2/ab/pic.png)"),
        )
        assertEquals(
            "File: notes.pdf",
            presentMessageContent("[notes.pdf](https://chat.example.com/user_uploads/notes.pdf)"),
        )
    }
}
