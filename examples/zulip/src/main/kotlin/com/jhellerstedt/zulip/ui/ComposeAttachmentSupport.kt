package com.jhellerstedt.zulip.ui

import android.net.Uri
import com.thelightphone.sdk.SealedLightContext
import java.io.File

private const val COMPOSE_ATTACHMENT_DIR = "zulip-compose-attachments"

data class ComposeAttachmentDraft(
    val displayName: String,
    val mimeType: String,
    val filePath: String,
    val sourceIndex: Int,
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

fun copyPickedPhotosToDrafts(
    lightContext: SealedLightContext,
    uris: List<Uri>,
    filesDir: File,
): Result<List<ComposeAttachmentDraft>> = runCatching {
    val attachmentRoot = File(filesDir, COMPOSE_ATTACHMENT_DIR).also { it.mkdirs() }
    val createdFiles = mutableListOf<File>()
    try {
        uris.mapIndexed { index, uri ->
            val mimeType = lightContext.getContentMimeType(uri).orEmpty()
            require(mimeType.startsWith("image/")) { "Selected file must be an image" }

            val displayName = lightContext.getContentDisplayName(uri)
                ?.takeIf { it.isNotBlank() }
                ?: defaultAttachmentName(index, mimeType)
            val targetFile = File(
                attachmentRoot,
                "${System.currentTimeMillis()}-$index-${sanitizeFileName(displayName)}",
            )
            check(lightContext.copyUriToFile(uri, targetFile)) { "Unable to open selected photo" }
            createdFiles += targetFile
            ComposeAttachmentDraft(
                displayName = displayName,
                mimeType = mimeType,
                filePath = targetFile.absolutePath,
                sourceIndex = index,
            )
        }
    } catch (error: Throwable) {
        createdFiles.forEach { it.delete() }
        throw error
    }
}

fun deleteDraftFiles(attachments: List<ComposeAttachmentDraft>) {
    attachments.forEach { File(it.filePath).delete() }
}

private fun defaultAttachmentName(index: Int, mimeType: String): String {
    val extension = mimeType.substringAfter('/', "jpg").ifBlank { "jpg" }
    return "photo-${index + 1}.$extension"
}

private fun sanitizeFileName(value: String): String {
    return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
