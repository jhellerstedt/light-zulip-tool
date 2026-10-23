package com.jhellerstedt.zulip.ui

const val GENERAL_CHAT_TOPIC_LABEL = "general chat"

fun topicDisplayName(name: String): String =
    if (name.isBlank()) GENERAL_CHAT_TOPIC_LABEL else name

fun isGeneralChatTopic(name: String): Boolean = name.isBlank()
