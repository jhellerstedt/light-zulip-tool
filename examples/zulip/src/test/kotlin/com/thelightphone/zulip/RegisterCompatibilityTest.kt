package com.thelightphone.zulip

import com.thelightphone.zulip.api.ZulipApiException
import com.thelightphone.zulip.store.shouldDowngradeIdleQueueTimeout
import com.thelightphone.zulip.store.shouldReregisterEventQueue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegisterCompatibilityTest {
    @Test
    fun detectsIdleQueueTimeoutCompatibilityError() {
        val error = ZulipApiException.ApiError(
            code = "BAD_REQUEST",
            message = "\"idle_queue_timeout\" is not valid JSON",
        )

        assertTrue(shouldDowngradeIdleQueueTimeout(error))
    }

    @Test
    fun ignoresOtherApiErrors() {
        val error = ZulipApiException.ApiError(
            code = "BAD_REQUEST",
            message = "Invalid channel ID",
        )

        assertFalse(shouldDowngradeIdleQueueTimeout(error))
    }

    @Test
    fun detectsStaleEventQueueError() {
        val error = ZulipApiException.ApiError(
            code = "BAD_EVENT_QUEUE_ID",
            message = "Bad event queue ID: 06b944fd-cb5b-4119-a0f5-b5b105d1ecd2",
        )

        assertTrue(shouldReregisterEventQueue(error))
    }

    @Test
    fun ignoresUnrelatedApiErrorsForEventQueueReregistration() {
        val error = ZulipApiException.ApiError(
            code = "BAD_REQUEST",
            message = "Invalid channel ID",
        )

        assertFalse(shouldReregisterEventQueue(error))
    }
}
