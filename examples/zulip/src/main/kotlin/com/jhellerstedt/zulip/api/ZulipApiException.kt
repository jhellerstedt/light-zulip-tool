package com.jhellerstedt.zulip.api

sealed class ZulipApiException(message: String) : Exception(message) {
    class ApiError(val code: String?, message: String) : ZulipApiException(message)
    class MalformedResponse(body: String) : ZulipApiException("Malformed response: $body")
    class Network(message: String) : ZulipApiException(message)
}
