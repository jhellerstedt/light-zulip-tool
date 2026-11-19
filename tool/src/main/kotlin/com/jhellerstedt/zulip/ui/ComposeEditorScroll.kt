package com.jhellerstedt.zulip.ui

import kotlin.math.roundToInt

/**
 * Scroll offset that keeps the compose caret inside the visible box.
 * Coordinates are in the scrolled content, with y growing downward.
 */
fun scrollOffsetToRevealCursor(
    scrollOffsetPx: Int,
    viewportHeightPx: Int,
    cursorTopPx: Float,
    cursorBottomPx: Float,
    maxScrollPx: Int,
): Int {
    if (viewportHeightPx <= 0) return scrollOffsetPx.coerceAtLeast(0)
    val viewportTop = scrollOffsetPx.toFloat()
    val viewportBottom = viewportTop + viewportHeightPx
    val target = when {
        cursorBottomPx > viewportBottom -> cursorBottomPx - viewportHeightPx
        cursorTopPx < viewportTop -> cursorTopPx
        else -> viewportTop
    }
    return target.roundToInt().coerceIn(0, maxScrollPx.coerceAtLeast(0))
}
