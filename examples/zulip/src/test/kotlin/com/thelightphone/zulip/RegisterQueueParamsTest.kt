package com.thelightphone.zulip

import com.thelightphone.zulip.api.models.RegisterQueueResponse
import com.thelightphone.zulip.api.routes.buildRegisterQueueFormParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegisterQueueParamsTest {
    @Test
    fun olderServersDoNotSendIdleQueueTimeout() {
        val params = buildRegisterQueueFormParams(zulipFeatureLevel = 480)

        assertFalse("idle_queue_timeout" in params)
    }

    @Test
    fun newerServersUseMobileIdleQueueTimeout() {
        val params = buildRegisterQueueFormParams(zulipFeatureLevel = 481)

        assertEquals("mobile", params["idle_queue_timeout"])
    }

    @Test
    fun registerFetchRequestsRealmDataForLongPollTimeout() {
        val params = buildRegisterQueueFormParams(zulipFeatureLevel = 481)

        assertTrue(params.getValue("fetch_event_types").contains("\"realm\""))
    }

    @Test
    fun registerFetchRequestsRealmUsersForDmTitles() {
        val params = buildRegisterQueueFormParams(zulipFeatureLevel = 481)

        assertTrue(params.getValue("fetch_event_types").contains("\"realm_user\""))
    }

    @Test
    fun registerFetchRequestsRecentPrivateConversations() {
        val params = buildRegisterQueueFormParams(zulipFeatureLevel = 481)

        assertTrue(params.getValue("fetch_event_types").contains("\"recent_private_conversations\""))
    }

    @Test
    fun olderServersFallBackToNinetySecondEventTimeout() {
        val response = RegisterQueueResponse(result = "success")

        assertEquals(90_000L, response.eventRequestTimeoutMillis())
    }

    @Test
    fun registerResponseUsesServerRecommendedEventTimeout() {
        val response = RegisterQueueResponse(
            result = "success",
            eventQueueLongpollTimeoutSeconds = 123,
        )

        assertEquals(123_000L, response.eventRequestTimeoutMillis())
    }
}
