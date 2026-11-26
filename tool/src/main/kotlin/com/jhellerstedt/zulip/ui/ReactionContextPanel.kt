package com.jhellerstedt.zulip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhellerstedt.zulip.util.ReactionEmoji
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable

private enum class ReactionPanelLevel {
    Actions,
    Reactions,
}

/**
 * Long-press panel for a Zulip message, matching the Chats reaction window:
 * LIKE sets a heart, REACT opens the same 3×8 emoji grid, and a message that
 * already has your reaction offers edit and remove. One reaction at a time.
 */
@Composable
fun ReactionContextPanel(
    visible: Boolean,
    hasOwnReaction: Boolean,
    onLike: () -> Unit,
    onReact: (ReactionEmoji) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    var level by remember { mutableStateOf(ReactionPanelLevel.Actions) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        when (level) {
            ReactionPanelLevel.Actions -> {
                val rows = buildList {
                    if (!hasOwnReaction) {
                        add("LIKE" to onLike)
                    }
                    if (hasOwnReaction) {
                        add("EDIT REACTION" to { level = ReactionPanelLevel.Reactions })
                        add("REMOVE REACTION" to onRemove)
                    } else {
                        add("REACT" to { level = ReactionPanelLevel.Reactions })
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 38.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    rows.forEach { (label, action) ->
                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier.lightClickable(onClick = action),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(
                                    text = label,
                                    variant = LightTextVariant.Button,
                                    color = Color.White,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            ReactionPanelLevel.Reactions -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                ReactionEmoji.rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                        row.forEach { emoji ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .lightClickable(onClick = { onReact(emoji) }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = emoji.glyph, fontSize = 24.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(48.dp, 38.dp)
                .lightClickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "⌄", color = Color.White, fontSize = 22.sp)
        }
    }
}
