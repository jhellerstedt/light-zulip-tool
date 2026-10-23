package com.jhellerstedt.zulip.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true }

sealed class Narrow {
    abstract fun toJson(): String
    abstract fun displayTitle(streams: Map<Int, String>, users: Map<Int, String>): String

    data class Topic(
        val streamId: Int,
        val topic: String,
    ) : Narrow() {
        override fun toJson(): String = json.encodeToString(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("operator", "stream")
                        put("operand", streamId)
                    },
                )
                add(
                    buildJsonObject {
                        put("operator", "topic")
                        put("operand", topic)
                    },
                )
            },
        )

        override fun displayTitle(streams: Map<Int, String>, users: Map<Int, String>): String {
            val streamName = streams[streamId] ?: "stream $streamId"
            return "$streamName > $topic"
        }
    }

    data class Channel(
        val streamId: Int,
    ) : Narrow() {
        override fun toJson(): String = json.encodeToString(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("operator", "stream")
                        put("operand", streamId)
                    },
                )
            },
        )

        override fun displayTitle(streams: Map<Int, String>, users: Map<Int, String>): String =
            streams[streamId] ?: "stream $streamId"
    }

    data class Dm(
        val userIds: List<Int>,
    ) : Narrow() {
        override fun toJson(): String = json.encodeToString(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("operator", "dm")
                        put("operand", buildJsonArray {
                            userIds.forEach { add(JsonPrimitive(it)) }
                        })
                    },
                )
            },
        )

        override fun displayTitle(streams: Map<Int, String>, users: Map<Int, String>): String =
            userIds.joinToString(", ") { userId -> users[userId] ?: "User $userId" }
    }
}

fun Narrow.normalized(): Narrow = when (this) {
    is Narrow.Topic -> this
    is Narrow.Channel -> this
    is Narrow.Dm -> Narrow.Dm(userIds.distinct().sorted())
}

fun Narrow.cacheKey(): String = when (val narrow = normalized()) {
    is Narrow.Topic -> "stream:${narrow.streamId}:${narrow.topic}"
    is Narrow.Channel -> "stream:${narrow.streamId}:"
    is Narrow.Dm -> "dm:${narrow.userIds.joinToString(",")}"
}

fun narrowFromCacheKey(value: String): Narrow? = when {
    value.startsWith("stream:") -> {
        val parts = value.removePrefix("stream:").split(":", limit = 2)
        val streamId = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val topic = parts.getOrNull(1).orEmpty()
        if (topic.isEmpty()) Narrow.Channel(streamId) else Narrow.Topic(streamId, topic)
    }
    value.startsWith("dm:") -> {
        val ids = value.removePrefix("dm:").split(",").mapNotNull(String::toIntOrNull).distinct().sorted()
        ids.takeIf { it.isNotEmpty() }?.let(Narrow::Dm)
    }
    else -> null
}

data class InboxConversation(
    val narrow: Narrow,
    val title: String,
    val unreadCount: Int,
    val preview: String? = null,
)
