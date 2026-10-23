package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.ui.GENERAL_CHAT_TOPIC_LABEL
import com.jhellerstedt.zulip.ui.isGeneralChatTopic
import com.jhellerstedt.zulip.ui.topicDisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicDisplayTest {
    @Test
    fun blankTopicIsShownAsGeneralChat() {
        assertEquals(GENERAL_CHAT_TOPIC_LABEL, topicDisplayName(""))
        assertEquals(GENERAL_CHAT_TOPIC_LABEL, topicDisplayName("   "))
        assertTrue(isGeneralChatTopic(""))
    }

    @Test
    fun namedTopicKeepsItsName() {
        assertEquals("release", topicDisplayName("release"))
        assertFalse(isGeneralChatTopic("release"))
    }
}
