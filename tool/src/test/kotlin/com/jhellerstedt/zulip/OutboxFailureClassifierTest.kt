package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipApiException
import com.jhellerstedt.zulip.store.OutboxFailureKind
import com.jhellerstedt.zulip.store.classifyOutboxFailure
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxFailureClassifierTest {
    @Test
    fun retryableFailuresAreTransient() {
        listOf(
            ZulipApiException.Network("DNS", UnknownHostException()),
            ZulipApiException.ApiError(408, null, "timeout"),
            ZulipApiException.ApiError(429, null, "rate limited"),
            ZulipApiException.ApiError(503, null, "unavailable"),
        ).forEach {
            assertEquals(OutboxFailureKind.TRANSIENT, classifyOutboxFailure(it))
        }
    }

    @Test
    fun nonRetryableClientFailuresArePermanent() {
        listOf(
            ZulipApiException.ApiError(401, null, "unauthorized"),
            ZulipApiException.ApiError(413, null, "too large"),
            ZulipApiException.ApiError(422, null, "bad recipient"),
        ).forEach {
            assertEquals(OutboxFailureKind.PERMANENT, classifyOutboxFailure(it))
        }
    }
}
