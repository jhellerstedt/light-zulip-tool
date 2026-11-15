package com.jhellerstedt.zulip.util

fun stripHtml(html: String): String {
    return html
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private val markdownLink = Regex("""\[([^\[\]]*)]\(([^)\s]+)\)""")
private val imageSuffix = Regex("""\.(?:png|jpe?g|gif|webp|bmp)(?:$|\?)""", RegexOption.IGNORE_CASE)
private val htmlAnchorOrImage = Regex(
    """<div\b[^>]*\bmessage_inline_image\b[^>]*>.*?</div>|<img\b([^>]*)/?>|<a\b([^>]*)>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val htmlAttribute = Regex("""\b([A-Za-z_:][\w:.-]*)\s*=\s*["']([^"']*)["']""")

sealed interface MessagePart {
    data class Text(val text: String) : MessagePart
    data class Photo(val name: String, val url: String) : MessagePart
    data class FileLink(val name: String, val url: String) : MessagePart
}

fun messageParts(content: String): List<MessagePart> {
    val hasHtmlMedia = content.contains("<img", ignoreCase = true) || content.contains("<a ", ignoreCase = true)
    return if (hasHtmlMedia) htmlMessageParts(content) else markdownMessageParts(content)
}

fun resolveRealmContentUrl(realmUrl: String, url: String): String {
    val reference = url.trim()
    if (reference.startsWith("https://") || reference.startsWith("http://")) return reference
    return realmUrl.trimEnd('/') + "/" + reference.trimStart('/')
}

fun presentMessageContent(content: String): String {
    val plain = stripHtml(content)
    return markdownLink.replace(plain) { match ->
        val name = match.groupValues[1].trim().ifBlank { "attachment" }
        val url = match.groupValues[2].substringBefore('?')
        if (imageSuffix.containsMatchIn(name) || imageSuffix.containsMatchIn(url)) {
            "Photo: $name"
        } else {
            "File: $name"
        }
    }.trim()
}

private fun markdownMessageParts(content: String): List<MessagePart> {
    val parts = mutableListOf<MessagePart>()
    var cursor = 0
    for (match in markdownLink.findAll(content)) {
        appendPlainText(parts, content.substring(cursor, match.range.first))
        parts += linkPart(match.groupValues[1], match.groupValues[2])
        cursor = match.range.last + 1
    }
    appendPlainText(parts, content.substring(cursor))
    return parts
}

private fun htmlMessageParts(content: String): List<MessagePart> {
    val previewHrefs = htmlAnchorOrImage.findAll(content).mapNotNull { match ->
        if (!match.value.startsWith("<div", ignoreCase = true)) return@mapNotNull null
        attribute(match.value, "href")?.substringBefore('?')
    }.toSet()
    val parts = mutableListOf<MessagePart>()
    var cursor = 0
    for (match in htmlAnchorOrImage.findAll(content)) {
        appendPlainText(parts, content.substring(cursor, match.range.first))
        val imageAttrs = match.groupValues[1]
        val anchorAttrs = match.groupValues[2]
        val anchorInner = match.groupValues[3]
        when {
            match.value.startsWith("<div", ignoreCase = true) -> {
                val href = attribute(match.value, "href")
                val imageSrc = attribute(match.value, "src")
                    ?.takeUnless { it.endsWith("loader-black.svg") }
                val url = href?.takeIf { it.isNotBlank() } ?: imageSrc
                if (url != null) {
                    val name = attribute(match.value, "title")
                        ?: attribute(match.value, "alt")
                        ?: fileName(url)
                    parts += linkPart(name, url)
                }
            }
            match.value.startsWith("<img", ignoreCase = true) -> {
                attribute(imageAttrs, "src")?.let { src ->
                    parts += linkPart(attribute(imageAttrs, "alt") ?: fileName(src), src)
                }
            }
            else -> {
                val href = attribute(anchorAttrs, "href")
                val alreadyPreviewed = href != null && href.substringBefore('?') in previewHrefs
                if (!alreadyPreviewed) {
                    val nestedSrc = attribute(anchorInner, "src")
                    val url = nestedSrc ?: href
                    if (url != null) {
                        val name = attribute(anchorInner, "alt")
                            ?: stripHtml(anchorInner).ifBlank { fileName(url) }
                        parts += linkPart(name, url)
                    }
                }
            }
        }
        cursor = match.range.last + 1
    }
    appendPlainText(parts, content.substring(cursor))
    return parts
}

private fun appendPlainText(parts: MutableList<MessagePart>, raw: String) {
    val text = stripHtml(raw)
    if (text.isNotBlank()) parts += MessagePart.Text(text)
}

private fun linkPart(name: String, url: String): MessagePart {
    val cleanName = name.trim().ifBlank { fileName(url) }.ifBlank { "attachment" }
    return if (imageSuffix.containsMatchIn(cleanName) || imageSuffix.containsMatchIn(url.substringBefore('?'))) {
        MessagePart.Photo(cleanName, url)
    } else {
        MessagePart.FileLink(cleanName, url)
    }
}

private fun attribute(source: String, name: String): String? =
    htmlAttribute.findAll(source).firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }?.groupValues?.get(2)

private fun fileName(url: String): String =
    url.substringBefore('?').substringAfterLast('/').trim()

fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val formatter = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(epochSeconds * 1000L))
}
