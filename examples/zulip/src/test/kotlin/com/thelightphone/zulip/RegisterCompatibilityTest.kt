package com.thelightphone.zulip

import com.thelightphone.zulip.api.ZulipApiException
import com.thelightphone.zulip.store.shouldDowngradeIdleQueueTimeout
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
}
