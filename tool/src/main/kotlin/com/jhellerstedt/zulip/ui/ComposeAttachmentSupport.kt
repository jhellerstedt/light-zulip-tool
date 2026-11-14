package com.jhellerstedt.zulip.ui

import java.io.File

data class ComposeAttachmentDraft(
    val displayName: String,
    val mimeType: String,
    val filePath: String,
    val sourceIndex: Int,
    val mediaId: Long? = null,
)

data class UploadedAttachmentLink(
    val displayName: String,
    val url: String,
)

fun buildUploadedFileMarkdown(displayName: String, url: String): String {
    return "[${sanitizeAttachmentName(displayName)}]($url)"
}

fun buildComposeMessageBody(
    caption: String,
    attachments: List<UploadedAttachmentLink>,
): String {
    val trimmedCaption = caption.trim()
    val attachmentBlock = attachments.joinToString("\n") {
        buildUploadedFileMarkdown(it.displayName, it.url)
    }
    return when {
        trimmedCaption.isEmpty() -> attachmentBlock
        attachmentBlock.isEmpty() -> trimmedCaption
        else -> "$trimmedCaption\n\n$attachmentBlock"
    }
}

fun buildAttachmentSummary(displayNames: List<String>): String {
    if (displayNames.isEmpty()) return "No photos selected"
    if (displayNames.size == 1) return "1 photo: ${displayNames.first()}"

    val preview = displayNames.take(2).joinToString(", ")
    val remaining = displayNames.size - 2
    val remainderSuffix = if (remaining > 0) " +$remaining more" else ""
    return "${displayNames.size} photos: $preview$remainderSuffix"
}

private fun sanitizeAttachmentName(displayName: String): String {
    return displayName.replace("[", "").replace("]", "")
}

fun imageMimeType(displayName: String): String {
    return when (displayName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }
}

fun writeComposeDraft(
    directory: File,
    displayName: String,
    bytes: ByteArray,
    sourceIndex: Int,
    mediaId: Long? = null,
): ComposeAttachmentDraft {
    directory.mkdirs()
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "photo.jpg" }
    val file = File(directory, "$sourceIndex-$safeName")
    file.writeBytes(bytes)
    return ComposeAttachmentDraft(
        displayName = displayName.ifBlank { safeName },
        mimeType = imageMimeType(displayName),
        filePath = file.absolutePath,
        sourceIndex = sourceIndex,
        mediaId = mediaId,
    )
}

fun deleteDraftFiles(attachments: List<ComposeAttachmentDraft>) {
    attachments.forEach { File(it.filePath).delete() }
}
