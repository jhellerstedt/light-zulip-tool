package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.util.MessagePart
import com.jhellerstedt.zulip.util.messageParts
import com.jhellerstedt.zulip.util.presentMessageContent
import com.jhellerstedt.zulip.util.resolveRealmContentUrl
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

    @Test
    fun messagePartsKeepCaptionAndPhotoFromMarkdown() {
        assertEquals(
            listOf(
                MessagePart.Text("Hello"),
                MessagePart.Photo("pic.png", "/user_uploads/2/ab/pic.png"),
            ),
            messageParts("Hello\n\n[pic.png](/user_uploads/2/ab/pic.png)"),
        )
    }

    @Test
    fun messagePartsReadZulipHtmlPhotos() {
        val html = """
            <p>Hello</p>
            <div class="message_inline_image">
              <a href="/user_uploads/2/ab/pic.png" title="pic.png">
                <img alt="pic.png" src="/user_uploads/2/ab/pic.png">
              </a>
            </div>
        """.trimIndent()

        assertEquals(
            listOf(
                MessagePart.Text("Hello"),
                MessagePart.Photo("pic.png", "/user_uploads/2/ab/pic.png"),
            ),
            messageParts(html),
        )
    }

    @Test
    fun resolveRealmContentUrlKeepsAbsoluteAndPrefixesRelative() {
        assertEquals(
            "https://chat.example.com/user_uploads/2/ab/pic.png",
            resolveRealmContentUrl("https://chat.example.com/", "/user_uploads/2/ab/pic.png"),
        )
        assertEquals(
            "https://cdn.example.com/pic.png",
            resolveRealmContentUrl("https://chat.example.com", "https://cdn.example.com/pic.png"),
        )
    }
}
