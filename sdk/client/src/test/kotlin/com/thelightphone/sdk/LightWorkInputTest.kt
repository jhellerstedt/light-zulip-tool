package com.thelightphone.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class LightWorkInputTest {
    @Test
    fun jobInputKeepsTheStoredStringValue() {
        val input = lightJobInput(
            mapOf(
                "account_id" to "42",
                "__light_job_key__" to "zulip-outbox",
            ),
        )

        assertEquals("42", input["account_id"])
        assertEquals(42L, input["account_id"]?.toLongOrNull())
    }
}
