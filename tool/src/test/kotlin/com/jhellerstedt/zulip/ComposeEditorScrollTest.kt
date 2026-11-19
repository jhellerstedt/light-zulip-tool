package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.ui.scrollOffsetToRevealCursor
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeEditorScrollTest {
    @Test
    fun scrollsDownWhenTheCursorPassesTheBottomOfTheComposeBox() {
        assertEquals(
            140,
            scrollOffsetToRevealCursor(
                scrollOffsetPx = 0,
                viewportHeightPx = 100,
                cursorTopPx = 220f,
                cursorBottomPx = 240f,
                maxScrollPx = 400,
            ),
        )
    }

    @Test
    fun scrollsUpWhenTheCursorMovesAboveTheVisibleComposeBox() {
        assertEquals(
            40,
            scrollOffsetToRevealCursor(
                scrollOffsetPx = 180,
                viewportHeightPx = 100,
                cursorTopPx = 40f,
                cursorBottomPx = 60f,
                maxScrollPx = 400,
            ),
        )
    }

    @Test
    fun leavesTheOffsetAloneWhenTheCursorIsAlreadyVisible() {
        assertEquals(
            80,
            scrollOffsetToRevealCursor(
                scrollOffsetPx = 80,
                viewportHeightPx = 100,
                cursorTopPx = 90f,
                cursorBottomPx = 110f,
                maxScrollPx = 400,
            ),
        )
    }

    @Test
    fun clampsTheFollowScrollToTheEndOfTheMessage() {
        assertEquals(
            50,
            scrollOffsetToRevealCursor(
                scrollOffsetPx = 0,
                viewportHeightPx = 100,
                cursorTopPx = 200f,
                cursorBottomPx = 220f,
                maxScrollPx = 50,
            ),
        )
    }
}
