package com.jhellerstedt.zulip.store

fun Map<String, Int>.hasStreamUnread(): Boolean =
    any { (key, count) ->
        count > 0 && when (narrowFromCacheKey(key)) {
            is Narrow.Channel, is Narrow.Topic -> true
            else -> false
        }
    }

fun Map<String, Int>.hasDirectUnread(): Boolean =
    any { (key, count) -> count > 0 && key.startsWith("dm:") }

fun Map<String, Int>.unreadStreamIds(): Set<Int> =
    mapNotNull { (key, count) ->
        if (count <= 0) return@mapNotNull null
        when (val narrow = narrowFromCacheKey(key)) {
            is Narrow.Topic -> narrow.streamId
            is Narrow.Channel -> narrow.streamId
            else -> null
        }
    }.toSet()

fun Map<String, Int>.unreadTopicNames(streamId: Int): Set<String> =
    mapNotNull { (key, count) ->
        if (count <= 0) return@mapNotNull null
        val topic = narrowFromCacheKey(key) as? Narrow.Topic ?: return@mapNotNull null
        if (topic.streamId != streamId) return@mapNotNull null
        topic.topic
    }.toSet()
