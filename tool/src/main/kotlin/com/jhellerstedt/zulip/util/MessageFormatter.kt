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

fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val formatter = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(epochSeconds * 1000L))
}
