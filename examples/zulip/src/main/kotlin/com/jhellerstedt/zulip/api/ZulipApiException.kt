package com.jhellerstedt.zulip.api

sealed class ZulipApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ApiError(
        val statusCode: Int,
        val code: String?,
        message: String,
    ) : ZulipApiException(message)

    class MalformedResponse(body: String) : ZulipApiException("Malformed response: $body")
    class Network(message: String, cause: Throwable? = null) : ZulipApiException(message, cause)
}
