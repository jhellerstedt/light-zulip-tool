package com.jhellerstedt.zulip.store

import com.jhellerstedt.zulip.api.ZulipClient
import com.jhellerstedt.zulip.api.models.RecentPrivateConversation
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.ZulipMessage
import com.jhellerstedt.zulip.api.models.ZulipStream
import com.jhellerstedt.zulip.api.models.ZulipTopic
import com.jhellerstedt.zulip.api.models.ZulipUser
import com.jhellerstedt.zulip.api.routes.getMessages
import com.jhellerstedt.zulip.api.routes.getStreamTopics
import com.jhellerstedt.zulip.api.routes.markMessagesRead
import com.jhellerstedt.zulip.api.routes.registerQueue
import com.jhellerstedt.zulip.api.routes.sendMessage
import com.jhellerstedt.zulip.api.routes.uploadFile
import com.jhellerstedt.zulip.data.CachedAccountSnapshot
import com.jhellerstedt.zulip.data.StoredZulipAccount
import com.jhellerstedt.zulip.ui.UploadedAttachmentLink
import com.jhellerstedt.zulip.ui.buildComposeMessageBody
import com.jhellerstedt.zulip.util.presentMessageContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

enum class ConnectionState { ONLINE, REFRESHING, OFFLINE }

data class HydrationResult(val hasCachedState: Boolean)

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

    private val _selectableUsers = MutableStateFlow<List<ZulipUser>>(emptyList())
    val selectableUsersFlow: StateFlow<List<ZulipUser>> = _selectableUsers.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.REFRESHING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun applyCachedSnapshot(snapshot: CachedAccountSnapshot) {
        streams.clear()
        snapshot.streams.forEach { stream -> streams[stream.streamId] = stream }
        users.clear()
        snapshot.users.forEach { user -> users[user.userId] = user }
        messagesByNarrow.clear()
        snapshot.messagesByNarrow.forEach { (narrow, cachedMessages) ->
            messagesByNarrow[narrow.cacheKey()] = cachedMessages.sortedBy { it.id }.toMutableList()
        }
        unreadByNarrow.clear()
        snapshot.conversations.forEach { conversation ->
            if (conversation.unreadCount > 0) {
                unreadByNarrow[conversation.narrowKey] = conversation.unreadCount
            }
        }
        // Union DM conversation rows (which mainly cover currently-unread DMs, since
        // toInboxConversations() only emits unread narrows) with DM narrows that only
        // have cached messages, so previously opened but now-read DMs still restore.
        val dmFromConversations = snapshot.conversations.mapNotNull { conversation ->
            val dm = narrowFromCacheKey(conversation.narrowKey) as? Narrow.Dm ?: return@mapNotNull null
            DirectMessageConversation(dm.userIds.sorted(), conversation.lastMessageId)
        }
        val dmFromMessages = snapshot.messagesByNarrow.mapNotNull { (narrow, cachedMessages) ->
            val dm = narrow as? Narrow.Dm ?: return@mapNotNull null
            val maxMessageId = cachedMessages.maxOfOrNull { it.id } ?: return@mapNotNull null
            DirectMessageConversation(dm.userIds.sorted(), maxMessageId)
        }
        _recentDirectMessages.value = (dmFromConversations + dmFromMessages)
            .groupBy { it.userIds }
            .map { (userIds, entries) -> DirectMessageConversation(userIds, entries.maxOf { it.maxMessageId }) }
            .sortedByDescending { it.maxMessageId }
        publishSelectableUsers()
        _streamsList.value = streams.values.sortedBy { it.name.lowercase() }
        rebuildInbox()
    }

    fun applyMessages(narrow: Narrow, messages: List<ZulipMessage>) {
        messagesByNarrow[narrow.cacheKey()] = messages.sortedBy { it.id }.toMutableList()
    }

    fun narrowForEvent(event: com.jhellerstedt.zulip.api.models.ZulipEvent): Narrow? =
        event.message?.let { narrowForMessage(it) }

    /**
     * Computes the [InboxConversation] projection an event would produce, without mutating
     * any state. Callers can use this to persist cache rows before [handleEvent] updates memory.
     */
    fun projectedConversationForEvent(event: com.jhellerstedt.zulip.api.models.ZulipEvent): InboxConversation? {
        val message = event.message ?: return null
        val narrow = narrowForMessage(message) ?: return null
        val key = narrow.cacheKey()
        val streamNames = streams.mapValues { it.value.name }
        val userNames = users.mapValues { it.value.fullName }
        val currentUnread = unreadByNarrow[key] ?: 0
        val projectedUnread = if (!message.flags.contains("read")) currentUnread + 1 else currentUnread
        return InboxConversation(
            narrow = narrow,
            title = narrow.displayTitle(streamNames, userNames),
            unreadCount = projectedUnread,
        )
    }

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
        streams.clear()
        response.subscriptions.forEach { stream ->
            streams[stream.streamId] = stream
        }
        users.clear()
        response.realmUsers.forEach { user ->
            users[user.userId] = user
        }
        syncRecentDirectMessages(response.recentPrivateConversations)
        response.messages.forEach { addMessage(it) }
        rebuildUnreadFromSnapshot(response.unreadMsgs)
        publishSelectableUsers()
        _streamsList.value = streams.values.sortedBy { it.name.lowercase() }
        rebuildInbox()
    }

    fun handleEvent(event: com.jhellerstedt.zulip.api.models.ZulipEvent) {
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
            "realm_user" -> {
                handleRealmUserEvent(event)
            }
            "heartbeat" -> Unit
        }
        rebuildInbox()
    }

    suspend fun loadMessages(narrow: Narrow): Result<List<ZulipMessage>> = runCatching {
        val key = narrow.cacheKey()
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
        val key = narrow.cacheKey()
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
        messagesByNarrow[narrow.cacheKey()].orEmpty()

    fun streamName(streamId: Int): String? = streams[streamId]?.name

    fun userName(userId: Int): String? = users[userId]?.fullName

    fun userNames(): Map<Int, String> = users.mapValues { it.value.fullName }

    fun selectableUsers(): List<ZulipUser> = selectableUsersFlow.value

    fun clearError() {
        _error.value = null
    }

    fun setError(message: String) {
        _error.value = message
    }

    fun updateAccountMetadata(account: StoredZulipAccount) {
        this.account = account
        publishSelectableUsers()
    }

    private fun publishSelectableUsers() {
        _selectableUsers.value = users.values
            .filter { it.userId != account.userId && it.isActive }
            .sortedBy { it.fullName.lowercase() }
    }

    private fun handleRealmUserEvent(event: com.jhellerstedt.zulip.api.models.ZulipEvent) {
        val person = event.person?.jsonObject ?: return
        val userId = person["user_id"]?.jsonPrimitive?.intOrNull ?: return
        when (event.op) {
            "add" -> {
                users[userId] = mergeRealmUserEventPerson(
                    existing = users[userId],
                    userId = userId,
                    person = person,
                )
                publishSelectableUsers()
            }
            "update" -> {
                val existing = users[userId]
                if (existing != null || person.containsKey("full_name") || person.containsKey("email") || person.containsKey("is_active")) {
                    users[userId] = mergeRealmUserEventPerson(
                        existing = existing,
                        userId = userId,
                        person = person,
                    )
                    publishSelectableUsers()
                }
            }
            "remove" -> {
                users.remove(userId)
                publishSelectableUsers()
            }
        }
    }

    private fun mergeRealmUserEventPerson(
        existing: ZulipUser?,
        userId: Int,
        person: Map<String, kotlinx.serialization.json.JsonElement>,
    ): ZulipUser = ZulipUser(
        userId = userId,
        fullName = person["full_name"]?.jsonPrimitive?.contentOrNull
            ?: existing?.fullName
            ?: "User $userId",
        email = person["email"]?.jsonPrimitive?.contentOrNull
            ?: existing?.email
            .orEmpty(),
        isActive = person["is_active"]?.jsonPrimitive?.booleanOrNull
            ?: existing?.isActive
            ?: true,
    )

    private fun addMessage(message: ZulipMessage) {
        val narrow = narrowForMessage(message) ?: return
        val key = narrow.cacheKey()
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
        messagesByNarrow.forEach { (key, messages) ->
            val index = messages.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                val message = messages[index]
                if (!message.flags.contains("read")) {
                    messages[index] = message.copy(flags = message.flags + "read")
                    unreadByNarrow[key] = maxOf(0, (unreadByNarrow[key] ?: 0) - 1)
                }
            }
        }
    }

    private fun rebuildUnreadFromSnapshot(unreadMsgs: com.jhellerstedt.zulip.api.models.UnreadMsgs?) {
        unreadByNarrow.clear()
        unreadMsgs?.streams?.forEach { unreadStream ->
            val narrow = if (unreadStream.topic.isBlank()) {
                Narrow.Channel(unreadStream.streamId)
            } else {
                Narrow.Topic(unreadStream.streamId, unreadStream.topic)
            }
            unreadByNarrow[narrow.cacheKey()] = unreadStream.unreadIds.size
        }
        unreadMsgs?.pms?.forEach { pm ->
            val key = Narrow.Dm(listOf(pm.senderId)).cacheKey()
            unreadByNarrow[key] = pm.unreadIds.size
        }
    }

    private fun rebuildInbox() {
        val streamNames = streams.mapValues { it.value.name }
        val userNames = users.mapValues { it.value.fullName }
        val conversations = mutableListOf<InboxConversation>()

        unreadByNarrow.forEach { (key, count) ->
            if (count <= 0) return@forEach
            val narrow = narrowFromCacheKey(key) ?: return@forEach
            val preview = messagesByNarrow[key]?.lastOrNull()?.let { presentMessageContent(it.content) }
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

}

data class DirectMessageConversation(
    val userIds: List<Int>,
    val maxMessageId: Int,
)
