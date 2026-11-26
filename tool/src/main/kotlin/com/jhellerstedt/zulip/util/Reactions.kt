package com.jhellerstedt.zulip.util

import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.api.models.ZulipReaction

/**
 * The Light Phone emoji panel, in the same 3×8 order Chats uses for Signal
 * reactions. [name] and [code] are the Zulip unicode reaction identity.
 */
data class ReactionEmoji(
    val glyph: String,
    val name: String,
    val code: String,
) {
    companion object {
        val like = ReactionEmoji("❤️", "heart", "2764")

        val rows: List<List<ReactionEmoji>> = listOf(
            listOf(
                ReactionEmoji("😅", "sweat_smile", "1f605"),
                ReactionEmoji("😊", "blush", "1f60a"),
                ReactionEmoji("🙄", "roll_eyes", "1f644"),
                ReactionEmoji("😍", "heart_eyes", "1f60d"),
                ReactionEmoji("😜", "stuck_out_tongue_winking_eye", "1f61c"),
                ReactionEmoji("😂", "joy", "1f602"),
                ReactionEmoji("😭", "sob", "1f62d"),
                ReactionEmoji("😎", "sunglasses", "1f60e"),
            ),
            listOf(
                ReactionEmoji("👏", "clap", "1f44f"),
                ReactionEmoji("👍", "thumbs_up", "1f44d"),
                ReactionEmoji("👎", "thumbs_down", "1f44e"),
                ReactionEmoji("🤞", "crossed_fingers", "1f91e"),
                ReactionEmoji("✌️", "v", "270c"),
                ReactionEmoji("👌", "ok_hand", "1f44c"),
                ReactionEmoji("👋", "wave", "1f44b"),
                ReactionEmoji("🙏", "pray", "1f64f"),
            ),
            listOf(
                ReactionEmoji("✨", "sparkles", "2728"),
                ReactionEmoji("🔥", "fire", "1f525"),
                like,
                ReactionEmoji("💔", "broken_heart", "1f494"),
                ReactionEmoji("🏆", "trophy", "1f3c6"),
                ReactionEmoji("🎯", "dart", "1f3af"),
                ReactionEmoji("👑", "crown", "1f451"),
                ReactionEmoji("👀", "eyes", "1f440"),
            ),
        )

        private val glyphsByCode: Map<String, ReactionEmoji> =
            rows.flatten().associateBy { it.code }

        fun byCode(code: String): ReactionEmoji? = glyphsByCode[code.lowercase()]
    }
}

fun ZulipReaction.reactorId(): Int {
    if (userId != 0) return userId
    val nested = user ?: return 0
    return if (nested.id != 0) nested.id else nested.userId
}

fun ZulipReaction.matches(emoji: ReactionEmoji): Boolean =
    emojiCode.equals(emoji.code, ignoreCase = true)

fun ZulipReaction.glyph(): String {
    ReactionEmoji.byCode(emojiCode)?.let { return it.glyph }
    val points = emojiCode.split('-').mapNotNull { part -> part.toIntOrNull(16) }
    if (points.isEmpty()) return emojiName
    return String(points.toIntArray(), 0, points.size)
}

fun ZulipMessage.withReactionEvent(
    op: String,
    userId: Int,
    emojiName: String,
    emojiCode: String,
    reactionType: String,
): ZulipMessage {
    val type = reactionType.ifBlank { "unicode_emoji" }
    val next = reactions.toMutableList()
    val index = next.indexOfFirst { reaction ->
        reaction.reactorId() == userId &&
            reaction.emojiCode.equals(emojiCode, ignoreCase = true) &&
            reaction.reactionType == type
    }
    when (op) {
        "add" -> if (index < 0) {
            next += ZulipReaction(
                emojiName = emojiName,
                emojiCode = emojiCode,
                reactionType = type,
                userId = userId,
            )
        }
        "remove" -> if (index >= 0) next.removeAt(index)
        else -> return this
    }
    return copy(reactions = next)
}

/** One reaction from this user, matching Signal: a new emoji replaces the old one. */
fun ZulipMessage.replacingOwnReaction(userId: Int, emoji: ReactionEmoji): ZulipMessage =
    copy(
        reactions = reactions.filterNot { it.reactorId() == userId } + ZulipReaction(
            emojiName = emoji.name,
            emojiCode = emoji.code,
            reactionType = "unicode_emoji",
            userId = userId,
        ),
    )

fun ZulipMessage.withoutOwnReactions(userId: Int): ZulipMessage =
    copy(reactions = reactions.filterNot { it.reactorId() == userId })

fun reactionSummary(
    message: ZulipMessage,
    ownUserId: Int,
    names: Map<Int, String>,
): String = message.reactions.joinToString(" · ") { reaction ->
    val who = when {
        reaction.reactorId() == ownUserId && ownUserId != 0 -> "You"
        else -> names[reaction.reactorId()]
            ?: reaction.user?.fullName?.takeIf { it.isNotBlank() }
            ?: "Someone"
    }
    "$who reacted ${reaction.glyph()}"
}
