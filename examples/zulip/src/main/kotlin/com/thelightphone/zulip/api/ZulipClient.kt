package com.thelightphone.zulip.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ZulipClient(
    private val realmUrl: String,
    private val email: String? = null,
    private val apiKey: String? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout)
        install(ContentNegotiation) {
            json(json)
        }
    }

    val isAuthenticated: Boolean
        get() = email != null && apiKey != null

    fun withCredentials(email: String, apiKey: String): ZulipClient =
        ZulipClient(realmUrl, email, apiKey)

    suspend fun get(
        path: String,
        params: Map<String, String> = emptyMap(),
        authenticated: Boolean = isAuthenticated,
        socketTimeoutMillis: Long? = null,
    ): String {
        val query = if (params.isEmpty()) {
            ""
        } else {
            "?" + params.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
        }
        val response = client.get("$apiBase/$path$query") {
            if (authenticated) {
                requireNotNull(email)
                requireNotNull(apiKey)
                basicAuth(email, apiKey)
            }
            header(HttpHeaders.UserAgent, USER_AGENT)
            if (socketTimeoutMillis != null) {
                timeout {
                    this.socketTimeoutMillis = socketTimeoutMillis
                }
            }
        }
        return parseResponse(response.status.value, response.bodyAsText())
    }

    suspend fun post(
        path: String,
        formParams: Map<String, String> = emptyMap(),
        authenticated: Boolean = isAuthenticated,
    ): String {
        val response = client.post("$apiBase/$path") {
            if (authenticated) {
                requireNotNull(email)
                requireNotNull(apiKey)
                basicAuth(email, apiKey)
            }
            header(HttpHeaders.UserAgent, USER_AGENT)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                formParams.forEach { (key, value) -> append(key, value) }
            }))
        }
        return parseResponse(response.status.value, response.bodyAsText())
    }

    private fun parseResponse(statusCode: Int, body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ZulipApiException.MalformedResponse(body.take(500)) }

        val result = root["result"]?.jsonPrimitive?.content
        if (statusCode !in 200..299 || result == "error") {
            val code = root["code"]?.jsonPrimitive?.content
            val msg = root["msg"]?.jsonPrimitive?.content ?: "HTTP $statusCode"
            throw ZulipApiException.ApiError(code, msg)
        }
        return body
    }

    fun close() {
        client.close()
    }

    private val apiBase: String
        get() = "${realmUrl.trimEnd('/')}/api/v1"

    companion object {
        private const val USER_AGENT = "ZulipLightOS/1.0"

        fun normalizeRealmUrl(input: String): String {
            var url = input.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            return url.trimEnd('/')
        }

        private fun encode(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
