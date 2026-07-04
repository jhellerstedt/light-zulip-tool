package com.thelightphone.zulip.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

private const val DEFAULT_EVENT_REQUEST_TIMEOUT_MILLIS = 90_000L

@Serializable
data class ZulipApiResult<T>(
    val result: String,
    val msg: String = "",
)

@Serializable
data class ServerSettingsResponse(
    val result: String,
    @SerialName("realm_name") val realmName: String = "",
    @SerialName("realm_uri") val realmUri: String = "",
    @SerialName("zulip_version") val zulipVersion: String = "",
    @SerialName("zulip_feature_level") val zulipFeatureLevel: Int = 0,
    @SerialName("authentication_methods") val authenticationMethods: AuthenticationMethods? = null,
)

@Serializable
data class AuthenticationMethods(
    val password: Boolean = false,
    val ldap: Boolean = false,
    val dev: Boolean = false,
)

@Serializable
data class FetchApiKeyResponse(
    val result: String,
    @SerialName("api_key") val apiKey: String = "",
    val email: String = "",
    @SerialName("user_id") val userId: Int = 0,
    val msg: String = "",
)

@Serializable
data class RegisterQueueResponse(
    val result: String,
    @SerialName("queue_id") val queueId: String = "",
    @SerialName("last_event_id") val lastEventId: Int = -1,
    @SerialName("event_queue_longpoll_timeout_seconds")
    val eventQueueLongpollTimeoutSeconds: Int? = null,
    val subscriptions: List<ZulipStream> = emptyList(),
    val messages: List<ZulipMessage> = emptyList(),
    @SerialName("realm_users") val realmUsers: List<ZulipUser> = emptyList(),
    @SerialName("recent_private_conversations")
    val recentPrivateConversations: List<RecentPrivateConversation> = emptyList(),
    @SerialName("unread_msgs") val unreadMsgs: UnreadMsgs? = null,
    val msg: String = "",
) {
    fun eventRequestTimeoutMillis(): Long =
        (eventQueueLongpollTimeoutSeconds?.toLong()?.times(1_000)) ?: DEFAULT_EVENT_REQUEST_TIMEOUT_MILLIS
}

@Serializable
data class RecentPrivateConversation(
    @SerialName("user_ids") val userIds: List<Int> = emptyList(),
    @SerialName("max_message_id") val maxMessageId: Int = 0,
)

@Serializable
data class GetEventsResponse(
    val result: String,
    val events: List<ZulipEvent> = emptyList(),
    val msg: String = "",
)

@Serializable
data class GetMessagesResponse(
    val result: String,
    val messages: List<ZulipMessage> = emptyList(),
    @SerialName("found_anchor") val foundAnchor: Boolean = false,
    @SerialName("found_newest") val foundNewest: Boolean = false,
    @SerialName("found_oldest") val foundOldest: Boolean = false,
    val msg: String = "",
)

@Serializable
data class SendMessageResponse(
    val result: String,
    val id: Int = 0,
    val msg: String = "",
)

@Serializable
data class UploadFileResponse(
    val result: String,
    val uri: String = "",
    val url: String = "",
    val filename: String = "",
    val msg: String = "",
)

@Serializable
data class StreamTopicsResponse(
    val result: String,
    val topics: List<ZulipTopic> = emptyList(),
    val msg: String = "",
)

@Serializable
data class ZulipTopic(
    val name: String,
    @SerialName("max_id") val maxId: Int = 0,
)

@Serializable
data class ZulipStream(
    val name: String,
    @SerialName("stream_id") val streamId: Int,
    val description: String = "",
    @SerialName("invite_only") val inviteOnly: Boolean = false,
)

@Serializable
data class ZulipUser(
    @SerialName("user_id") val userId: Int,
    @SerialName("full_name") val fullName: String,
    val email: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class ZulipMessage(
    val id: Int,
    val type: String,
    val content: String,
    val topic: String = "",
    @SerialName("stream_id") val streamId: Int? = null,
    @SerialName("display_recipient") val displayRecipient: JsonElement? = null,
    val subject: String = "",
    val sender: ZulipUser? = null,
    @SerialName("sender_full_name") val senderFullName: String = "",
    @SerialName("sender_id") val senderId: Int? = null,
    val timestamp: Long = 0,
    val flags: List<String> = emptyList(),
)

@Serializable
data class UnreadMsgs(
    val count: Int = 0,
    val streams: List<UnreadStream> = emptyList(),
    val pms: List<UnreadPm> = emptyList(),
    val huddles: List<UnreadHuddle> = emptyList(),
)

@Serializable
data class UnreadStream(
    @SerialName("stream_id") val streamId: Int,
    val topic: String = "",
    @SerialName("unread_message_ids") private val unreadMessageIds: List<Int> = emptyList(),
    @SerialName("unread_ids") private val unreadIdsLegacy: List<Int> = emptyList(),
) {
    val unreadIds: List<Int>
        get() = if (unreadMessageIds.isNotEmpty()) unreadMessageIds else unreadIdsLegacy
}

@Serializable
data class UnreadPm(
    @SerialName("sender_id") val senderId: Int,
    @SerialName("unread_message_ids") private val unreadMessageIds: List<Int> = emptyList(),
    @SerialName("unread_ids") private val unreadIdsLegacy: List<Int> = emptyList(),
) {
    val unreadIds: List<Int>
        get() = if (unreadMessageIds.isNotEmpty()) unreadMessageIds else unreadIdsLegacy
}

@Serializable
data class UnreadHuddle(
    @SerialName("user_ids_string") val userIdsString: String,
    @SerialName("unread_message_ids") private val unreadMessageIds: List<Int> = emptyList(),
    @SerialName("unread_ids") private val unreadIdsLegacy: List<Int> = emptyList(),
) {
    val unreadIds: List<Int>
        get() = if (unreadMessageIds.isNotEmpty()) unreadMessageIds else unreadIdsLegacy
}

@Serializable
data class ZulipEvent(
    val type: String,
    val id: Int = -1,
    val message: ZulipMessage? = null,
    val messages: List<Int>? = null,
    val person: JsonElement? = null,
    val operation: String? = null,
    val flag: String? = null,
    val subscriptions: List<ZulipStream>? = null,
    val op: String? = null,
)
