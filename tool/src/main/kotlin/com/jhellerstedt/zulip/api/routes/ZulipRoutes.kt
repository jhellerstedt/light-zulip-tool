package com.jhellerstedt.zulip.api.routes

import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.models.FetchApiKeyResponse
import com.jhellerstedt.zulip.api.models.GetEventsResponse
import com.jhellerstedt.zulip.api.models.GetMessagesResponse
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.SendMessageResponse
import com.jhellerstedt.zulip.api.models.ServerSettingsResponse
import com.jhellerstedt.zulip.api.models.StreamTopicsResponse
import com.jhellerstedt.zulip.api.models.UploadFileResponse
import com.jhellerstedt.zulip.store.Narrow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private const val IDLE_QUEUE_TIMEOUT_FEATURE_LEVEL = 481

suspend fun fetchServerSettings(client: ZulipClient): ServerSettingsResponse {
    val body = client.get("server_settings", authenticated = false)
    return json.decodeFromString(body)
}

suspend fun fetchApiKey(
    client: ZulipClient,
    username: String,
    password: String,
): FetchApiKeyResponse {
    val body = client.post(
        "fetch_api_key",
        formParams = mapOf(
            "username" to username,
            "password" to password,
        ),
        authenticated = false,
    )
    return json.decodeFromString(body)
}

suspend fun registerQueue(
    client: ZulipClient,
    zulipFeatureLevel: Int,
): RegisterQueueResponse {
    val body = client.post(
        "register",
        formParams = buildRegisterQueueFormParams(zulipFeatureLevel),
    )
    return json.decodeFromString(body)
}

fun buildRegisterQueueFormParams(zulipFeatureLevel: Int): Map<String, String> {
    val params = mutableMapOf(
        "event_types" to json.encodeToString(
            listOf("message", "subscription", "update_message_flags", "realm_user"),
        ),
        "fetch_event_types" to json.encodeToString(
            listOf("message", "subscription", "realm", "realm_user", "recent_private_conversations"),
        ),
        "apply_markdown" to "false",
        "slim_presence" to "true",
        "client_gravatar" to "true",
        "include_subscribers" to "false",
        "pushable" to "false",
    )
    if (zulipFeatureLevel >= IDLE_QUEUE_TIMEOUT_FEATURE_LEVEL) {
        params["idle_queue_timeout"] = "mobile"
    }
    return params
}

suspend fun getEvents(
    client: ZulipClient,
    queueId: String,
    lastEventId: Int,
    eventRequestTimeoutMillis: Long,
    dontBlock: Boolean = false,
): GetEventsResponse {
    val params = mutableMapOf(
        "queue_id" to queueId,
        "last_event_id" to lastEventId.toString(),
    )
    if (dontBlock) {
        params["dont_block"] = "true"
    }
    val body = client.get(
        "events",
        params,
        socketTimeoutMillis = eventRequestTimeoutMillis,
    )
    return json.decodeFromString(body)
}

suspend fun getStreamTopics(
    client: ZulipClient,
    streamId: Int,
): StreamTopicsResponse {
    val body = client.get(
        "users/me/$streamId/topics",
        params = mapOf("allow_empty_topic_name" to "true"),
    )
    return json.decodeFromString(body)
}

suspend fun getMessages(
    client: ZulipClient,
    narrow: Narrow,
    numBefore: Int = 25,
    anchor: String = "newest",
): GetMessagesResponse {
    val body = client.get(
        "messages",
        params = mapOf(
            "anchor" to anchor,
            "num_before" to numBefore.toString(),
            "num_after" to "0",
            "narrow" to narrow.toJson(),
        ),
    )
    return json.decodeFromString(body)
}

suspend fun sendMessage(
    client: ZulipClient,
    narrow: Narrow,
    content: String,
): SendMessageResponse {
    val formParams = when (narrow) {
        is Narrow.Topic -> mapOf(
            "type" to "stream",
            "to" to narrow.streamId.toString(),
            "topic" to narrow.topic,
            "content" to content,
        )
        is Narrow.Channel -> mapOf(
            "type" to "stream",
            "to" to narrow.streamId.toString(),
            "topic" to "general",
            "content" to content,
        )
        is Narrow.Dm -> mapOf(
            "type" to "direct",
            "to" to json.encodeToString(narrow.userIds),
            "content" to content,
        )
    }
    val body = client.post("messages", formParams = formParams)
    return json.decodeFromString(body)
}

suspend fun uploadFile(
    client: ZulipClient,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
): UploadFileResponse {
    val body = client.postMultipart(
        path = "user_uploads",
        fileFieldName = "filename",
        fileName = fileName,
        mimeType = mimeType,
        bytes = bytes,
    )
    return json.decodeFromString(body)
}

suspend fun markMessagesRead(
    client: ZulipClient,
    messageIds: List<Int>,
) {
    if (messageIds.isEmpty()) return
    client.post(
        "messages/flags",
        formParams = mapOf(
            "messages" to json.encodeToString(messageIds),
            "op" to "add",
            "flag" to "read",
        ),
    )
}
