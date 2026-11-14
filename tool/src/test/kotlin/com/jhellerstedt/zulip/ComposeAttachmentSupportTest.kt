package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.ui.UploadedAttachmentLink
import com.jhellerstedt.zulip.ui.buildAttachmentSummary
import com.jhellerstedt.zulip.ui.buildComposeMessageBody
import com.jhellerstedt.zulip.ui.buildUploadedFileMarkdown
import com.jhellerstedt.zulip.ui.writeComposeDraft
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeAttachmentSupportTest {
    @Test
    fun sanitizesAttachmentNamesWhenBuildingMarkdownLinks() {
        val markdown = buildUploadedFileMarkdown(
            displayName = "beach [day].jpg",
            url = "/user_uploads/1/beach.jpg",
        )

        assertEquals("[beach day.jpg](/user_uploads/1/beach.jpg)", markdown)
    }

    @Test
    fun buildsMessageBodyWithCaptionBlankLineAndOrderedLinks() {
        val message = buildComposeMessageBody(
            caption = "Weekend photos",
            attachments = listOf(
                UploadedAttachmentLink(
                    displayName = "first.jpg",
                    url = "/user_uploads/1/first.jpg",
                ),
                UploadedAttachmentLink(
                    displayName = "second.jpg",
                    url = "/user_uploads/1/second.jpg",
                ),
            ),
        )

        assertEquals(
            """
            Weekend photos

            [first.jpg](/user_uploads/1/first.jpg)
            [second.jpg](/user_uploads/1/second.jpg)
            """.trimIndent(),
            message,
        )
    }

    @Test
    fun summarizesMultipleAttachmentsForComposePreview() {
        val summary = buildAttachmentSummary(
            listOf("first.jpg", "second.jpg", "third.jpg"),
        )

        assertEquals("3 photos: first.jpg, second.jpg +1 more", summary)
    }

    @Test
    fun writesAPhotoDraftTheOutboxCanUpload() {
        val directory = File("build/compose-draft-test").apply { mkdirs() }
        val draft = writeComposeDraft(
            directory = directory,
            displayName = "beach.png",
            bytes = byteArrayOf(1, 2, 3),
            sourceIndex = 0,
        )

        assertEquals("beach.png", draft.displayName)
        assertEquals("image/png", draft.mimeType)
        assertEquals(0, draft.sourceIndex)
        assertEquals(byteArrayOf(1, 2, 3).toList(), File(draft.filePath).readBytes().toList())
        assertTrue(draft.filePath.startsWith(directory.absolutePath))
        File(draft.filePath).delete()
    }
}
