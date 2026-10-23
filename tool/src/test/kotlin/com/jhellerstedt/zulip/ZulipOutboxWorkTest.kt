package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.store.OutboxRunResult
import com.jhellerstedt.zulip.store.outboxWorkName
import com.jhellerstedt.zulip.store.runOutboxJob
import com.thelightphone.sdk.LightJobResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ZulipOutboxWorkTest {
    @Test
    fun workNameIsAccountScoped() {
        assertEquals("zulip-outbox-42", outboxWorkName(42))
    }

    @Test
    fun handlerRejectsMissingAccountId() = runTest {
        val result = runOutboxJob(emptyMap()) { error("processor should not run") }

        assertIs<LightJobResult.Error>(result)
    }

    @Test
    fun handlerMapsSuccessfulOutboxRunToSuccess() = runTest {
        val result = runOutboxJob(mapOf("account_id" to "42")) {
            assertEquals(42L, it)
            OutboxRunResult.Success
        }

        assertIs<LightJobResult.Success>(result)
    }

    @Test
    fun handlerMapsRetryingOutboxRunToRetry() = runTest {
        val result = runOutboxJob(mapOf("account_id" to "42")) {
            assertEquals(42L, it)
            OutboxRunResult.Retry
        }

        assertIs<LightJobResult.Retry>(result)
    }
}
