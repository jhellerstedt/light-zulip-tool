package com.thelightphone.zulip.util

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

fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val formatter = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(epochSeconds * 1000L))
}
