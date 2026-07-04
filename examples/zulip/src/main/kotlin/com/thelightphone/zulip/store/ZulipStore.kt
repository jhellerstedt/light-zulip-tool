package com.thelightphone.zulip.store

import com.thelightphone.zulip.api.ZulipClient
import com.thelightphone.zulip.api.models.RecentPrivateConversation
import com.thelightphone.zulip.api.models.RegisterQueueResponse
import com.thelightphone.zulip.api.models.ZulipMessage
import com.thelightphone.zulip.api.models.ZulipStream
import com.thelightphone.zulip.api.models.ZulipTopic
import com.thelightphone.zulip.api.models.ZulipUser
import com.thelightphone.zulip.api.routes.getMessages
import com.thelightphone.zulip.api.routes.getStreamTopics
import com.thelightphone.zulip.api.routes.markMessagesRead
import com.thelightphone.zulip.api.routes.registerQueue
import com.thelightphone.zulip.api.routes.sendMessage
import com.thelightphone.zulip.api.routes.uploadFile
import com.thelightphone.zulip.data.StoredZulipAccount
import com.thelightphone.zulip.ui.UploadedAttachmentLink
import com.thelightphone.zulip.ui.buildComposeMessageBody
import com.thelightphone.zulip.util.stripHtml
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class PendingUploadAttachment(
    val displayName: String,
    val mimeType: String,
    val file: File,
)

class ZulipStore(
    account: StoredZulipAccount,
    val client: ZulipClient,
) {
    var account: StoredZulipAccount = account
        private set
    var eventRequestTimeoutMillis: Long = 90_000L
        private set
    var queueId: String = ""
        private set
    var lastEventId: Int = -1
        private set

    private val streams = mutableMapOf<Int, ZulipStream>()
    private val users = mutableMapOf<Int, ZulipUser>()
    private val messagesByNarrow = mutableMapOf<String, MutableList<ZulipMessage>>()
    private val unreadByNarrow = mutableMapOf<String, Int>()

    private val _inbox = MutableStateFlow<List<InboxConversation>>(emptyList())
    val inbox: StateFlow<List<InboxConversation>> = _inbox.asStateFlow()

    private val _streamsList = MutableStateFlow<List<ZulipStream>>(emptyList())
    val streamsList: StateFlow<List<ZulipStream>> = _streamsList.asStateFlow()

    private val _recentDirectMessages = MutableStateFlow<List<DirectMessageConversation>>(emptyList())
    val recentDirectMessages: StateFlow<List<DirectMessageConversation>> = _recentDirectMessages.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    suspend fun register(): Result<Unit> = runCatching {
        _isLoading.value = true
        try {
            val response = registerQueue(client, account.zulipFeatureLevel)
            applyRegisterResponse(response)
        } finally {
            _isLoading.value = false
        }
    }

    fun applyRegisterResponse(response: RegisterQueueResponse) {
        eventRequestTimeoutMillis = response.eventRequestTimeoutMillis()
        queueId = response.queueId
        lastEventId = response.lastEventId
        response.subscriptions.forEach { stream ->
            streams[stream.streamId] = stream
        }
        response.realmUsers.forEach { user ->
            users[user.userId] = user
        }
        syncRecentDirectMessages(response.recentPrivateConversations)
        response.messages.forEach { addMessage(it) }
        rebuildUnreadFromSnapshot(response.unreadMsgs)
        _streamsList.value = streams.values.sortedBy { it.name.lowercase() }
        rebuildInbox()
    }

    fun handleEvent(event: com.thelightphone.zulip.api.models.ZulipEvent) {
        if (event.id > lastEventId) {
            lastEventId = event.id
        }
        when (event.type) {
            "message" -> event.message?.let {
                addMessage(it)
                updateRecentDirectMessages(it)
            }
            "subscription" -> {
                when (event.op) {
                    "add" -> event.subscriptions?.forEach { stream ->
                        streams[stream.streamId] = stream
                    }
                    "remove" -> event.subscriptions?.forEach { stream ->
                        streams.remove(stream.streamId)
                    }
                }
                _streamsList.value = streams.values.sortedBy { it.name.lowercase() }
            }
            "update_message_flags" -> {
                if (event.operation == "add" && event.flag == "read") {
                    event.messages?.forEach { messageId ->
                        markMessageReadLocally(messageId)
                    }
                }
            }
            "heartbeat" -> Unit
        }
        rebuildInbox()
    }

    suspend fun loadMessages(narrow: Narrow): Result<List<ZulipMessage>> = runCatching {
        val key = narrowKey(narrow)
        val response = getMessages(client, narrow)
        val sorted = response.messages.sortedBy { it.id }
        messagesByNarrow[key] = sorted.toMutableList()
        sorted
    }

    suspend fun loadTopics(streamId: Int): Result<List<ZulipTopic>> = runCatching {
        getStreamTopics(client, streamId).topics.sortedByDescending { it.maxId }
    }

    suspend fun send(
        narrow: Narrow,
        content: String,
        attachments: List<PendingUploadAttachment> = emptyList(),
    ): Result<Int> = runCatching {
        val finalContent = if (attachments.isEmpty()) {
            content
        } else {
            val uploadedLinks = attachments.map { attachment ->
                require(attachment.file.isFile) { "Selected photo is no longer available" }
                val response = uploadFile(
                    client = client,
                    fileName = attachment.displayName,
                    mimeType = attachment.mimeType,
                    bytes = attachment.file.readBytes(),
                )
                UploadedAttachmentLink(
                    displayName = response.filename.ifBlank { attachment.displayName },
                    url = response.url.ifBlank { response.uri },
                )
            }
            buildComposeMessageBody(content, uploadedLinks)
        }
        val response = sendMessage(client, narrow, finalContent)
        response.id
    }

    suspend fun markNarrowRead(narrow: Narrow) {
        val key = narrowKey(narrow)
        val unreadIds = messagesByNarrow[key]
            ?.filter { !it.flags.contains("read") }
            ?.map { it.id }
            .orEmpty()
        if (unreadIds.isNotEmpty()) {
            runCatching { markMessagesRead(client, unreadIds) }
            unreadIds.forEach { markMessageReadLocally(it) }
            unreadByNarrow[key] = 0
            rebuildInbox()
        }
    }

    fun getMessagesFor(narrow: Narrow): List<ZulipMessage> =
        messagesByNarrow[narrowKey(narrow)].orEmpty()

    fun streamName(streamId: Int): String? = streams[streamId]?.name

    fun userName(userId: Int): String? = users[userId]?.fullName

    fun userNames(): Map<Int, String> = users.mapValues { it.value.fullName }

    fun selectableUsers(): List<ZulipUser> = users.values
        .filter { it.userId != account.userId }
        .sortedBy { it.fullName.lowercase() }

    fun clearError() {
        _error.value = null
    }

    fun setError(message: String) {
        _error.value = message
    }

    fun updateAccountMetadata(account: StoredZulipAccount) {
        this.account = account
    }

    private fun addMessage(message: ZulipMessage) {
        val narrow = narrowForMessage(message) ?: return
        val key = narrowKey(narrow)
        val list = messagesByNarrow.getOrPut(key) { mutableListOf() }
        val existingIndex = list.indexOfFirst { it.id == message.id }
        if (existingIndex >= 0) {
            list[existingIndex] = message
        } else {
            list.add(message)
            list.sortBy { it.id }
        }
        if (!message.flags.contains("read")) {
            unreadByNarrow[key] = (unreadByNarrow[key] ?: 0) + 1
        }
    }

    private fun markMessageReadLocally(messageId: Int) {
        messagesByNarrow.values.forEach { messages ->
            val index = messages.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                val message = messages[index]
                if (!message.flags.contains("read")) {
                    messages[index] = message.copy(flags = message.flags + "read")
                }
            }
        }
        unreadByNarrow.keys.toList().forEach { key ->
            val unread = messagesByNarrow[key]?.count { !it.flags.contains("read") } ?: 0
            unreadByNarrow[key] = unread
        }
    }

    private fun rebuildUnreadFromSnapshot(unreadMsgs: com.thelightphone.zulip.api.models.UnreadMsgs?) {
        unreadByNarrow.clear()
        unreadMsgs?.streams?.forEach { unreadStream ->
            val narrow = if (unreadStream.topic.isBlank()) {
                Narrow.Channel(unreadStream.streamId)
            } else {
                Narrow.Topic(unreadStream.streamId, unreadStream.topic)
            }
            unreadByNarrow[narrowKey(narrow)] = unreadStream.unreadIds.size
        }
        unreadMsgs?.pms?.forEach { pm ->
            val key = narrowKey(Narrow.Dm(listOf(pm.senderId)))
            unreadByNarrow[key] = pm.unreadIds.size
        }
    }

    private fun rebuildInbox() {
        val streamNames = streams.mapValues { it.value.name }
        val userNames = users.mapValues { it.value.fullName }
        val conversations = mutableListOf<InboxConversation>()

        unreadByNarrow.forEach { (key, count) ->
            if (count <= 0) return@forEach
            val narrow = narrowFromKey(key) ?: return@forEach
            val preview = messagesByNarrow[key]?.lastOrNull()?.let { stripHtml(it.content) }
            conversations.add(
                InboxConversation(
                    narrow = narrow,
                    title = narrow.displayTitle(streamNames, userNames),
                    unreadCount = count,
                    preview = preview,
                ),
            )
        }

        if (conversations.isEmpty()) {
            streams.values.sortedBy { it.name.lowercase() }.take(10).forEach { stream ->
                conversations.add(
                    InboxConversation(
                        narrow = Narrow.Channel(stream.streamId),
                        title = stream.name,
                        unreadCount = 0,
                        preview = stream.description.ifBlank { null },
                    ),
                )
            }
        }

        _inbox.value = conversations.sortedWith(
            compareByDescending<InboxConversation> { it.unreadCount }
                .thenBy { it.title.lowercase() },
        )
    }

    private fun narrowForMessage(message: ZulipMessage): Narrow? {
        return when (message.type) {
            "stream" -> {
                val streamId = message.streamId ?: return null
                val topic = message.subject.ifBlank { message.topic }
                Narrow.Topic(streamId, topic)
            }
            "private" -> Narrow.Dm(dmOtherUserIds(message))
            else -> null
        }
    }

    private fun syncRecentDirectMessages(conversations: List<RecentPrivateConversation>) {
        _recentDirectMessages.value = conversations
            .map { DirectMessageConversation(it.userIds.sorted(), it.maxMessageId) }
            .sortedByDescending { it.maxMessageId }
    }

    private fun updateRecentDirectMessages(message: ZulipMessage) {
        if (message.type != "private") return
        val userIds = dmOtherUserIds(message).sorted()
        val updated = _recentDirectMessages.value
            .filterNot { it.userIds == userIds }
            .toMutableList()
        updated.add(0, DirectMessageConversation(userIds, message.id))
        _recentDirectMessages.value = updated
    }

    private fun dmOtherUserIds(message: ZulipMessage): List<Int> {
        val idsFromRecipient = runCatching { message.displayRecipient?.jsonArray }
            .getOrNull()
            ?.mapNotNull { recipient ->
                recipient.jsonObject["id"]?.jsonPrimitive?.intOrNull
                    ?: recipient.jsonObject["user_id"]?.jsonPrimitive?.intOrNull
            }
            ?.filter { it != account.userId }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        if (idsFromRecipient.isNotEmpty() || message.senderId == account.userId) {
            return idsFromRecipient
        }
        val senderId = message.senderId ?: message.sender?.userId
        return if (senderId != null && senderId != account.userId) listOf(senderId) else emptyList()
    }

    private fun narrowKey(narrow: Narrow): String = when (narrow) {
        is Narrow.Topic -> "stream:${narrow.streamId}:${narrow.topic}"
        is Narrow.Channel -> "stream:${narrow.streamId}:"
        is Narrow.Dm -> "dm:${narrow.userIds.sorted().joinToString(",")}"
    }

    private fun narrowFromKey(key: String): Narrow? {
        return when {
            key.startsWith("stream:") -> {
                val parts = key.removePrefix("stream:").split(":", limit = 2)
                val streamId = parts[0].toIntOrNull() ?: return null
                val topic = parts.getOrNull(1).orEmpty()
                if (topic.isEmpty()) Narrow.Channel(streamId) else Narrow.Topic(streamId, topic)
            }
            key.startsWith("dm:") -> {
                val ids = key.removePrefix("dm:").split(",").mapNotNull { it.toIntOrNull() }
                if (ids.isEmpty()) null else Narrow.Dm(ids)
            }
            else -> null
        }
    }
}

data class DirectMessageConversation(
    val userIds: List<Int>,
    val maxMessageId: Int,
)
