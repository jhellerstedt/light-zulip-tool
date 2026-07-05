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

data class InboxConversation(
    val narrow: Narrow,
    val title: String,
    val unreadCount: Int,
    val preview: String? = null,
)
