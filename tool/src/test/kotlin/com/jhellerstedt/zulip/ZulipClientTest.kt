package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.api.ZulipApiException
import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.store.OutboxFailureKind
import com.jhellerstedt.zulip.store.classifyOutboxFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ZulipClientTest {
    @Test
    fun malformedFiveHundredResponseIsAnApiError() {
        val client = ZulipClient("https://example.com")

        val error = assertIs<ZulipApiException.ApiError>(
            kotlin.runCatching {
                client.parseResponse(503, "<html>service unavailable</html>")
            }.exceptionOrNull(),
        )

        assertEquals(503, error.statusCode)
        assertEquals(null, error.code)
        assertEquals(OutboxFailureKind.TRANSIENT, classifyOutboxFailure(error))
        client.close()
    }

    @Test
    fun explicitHttpRealmUrlIsRejected() {
        assertEquals(
            "Server URL must use HTTPS",
            ZulipClient.httpsRealmUrlError("http://chat.example.com"),
        )
        assertEquals(
            "Server URL must use HTTPS",
            ZulipClient.httpsRealmUrlError("  HTTP://chat.example.com/  "),
        )
        assertEquals(null, ZulipClient.httpsRealmUrlError("chat.example.com"))
        assertEquals(null, ZulipClient.httpsRealmUrlError("https://chat.example.com"))
    }
}
