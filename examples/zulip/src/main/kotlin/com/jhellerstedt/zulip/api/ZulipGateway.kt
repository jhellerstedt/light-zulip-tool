package com.jhellerstedt.zulip.api

import com.jhellerstedt.zulip.api.models.GetEventsResponse
import com.jhellerstedt.zulip.api.models.GetMessagesResponse
import com.jhellerstedt.zulip.api.models.RegisterQueueResponse
import com.jhellerstedt.zulip.api.models.SendMessageResponse
import com.jhellerstedt.zulip.api.models.StreamTopicsResponse
import com.jhellerstedt.zulip.api.models.UploadFileResponse
import com.jhellerstedt.zulip.api.routes.getEvents
import com.jhellerstedt.zulip.api.routes.getMessages
import com.jhellerstedt.zulip.api.routes.getStreamTopics
import com.jhellerstedt.zulip.api.routes.registerQueue
import com.jhellerstedt.zulip.api.routes.sendMessage
import com.jhellerstedt.zulip.api.routes.uploadFile
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.PendingUploadAttachment

interface ZulipGateway {
    suspend fun register(client: ZulipClient, featureLevel: Int): RegisterQueueResponse

    suspend fun events(
        client: ZulipClient,
        queueId: String,
        lastEventId: Int,
        timeoutMillis: Long,
        dontBlock: Boolean,
    ): GetEventsResponse

    suspend fun messages(client: ZulipClient, narrow: Narrow): GetMessagesResponse

    suspend fun topics(client: ZulipClient, streamId: Int): StreamTopicsResponse

    suspend fun upload(client: ZulipClient, attachment: PendingUploadAttachment): UploadFileResponse

    suspend fun send(client: ZulipClient, narrow: Narrow, content: String): SendMessageResponse
}

class DefaultZulipGateway : ZulipGateway {
    override suspend fun register(client: ZulipClient, featureLevel: Int): RegisterQueueResponse =
        registerQueue(client, featureLevel)

    override suspend fun events(
        client: ZulipClient,
        queueId: String,
        lastEventId: Int,
        timeoutMillis: Long,
        dontBlock: Boolean,
    ): GetEventsResponse = getEvents(
        client = client,
        queueId = queueId,
        lastEventId = lastEventId,
        eventRequestTimeoutMillis = timeoutMillis,
        dontBlock = dontBlock,
    )

    override suspend fun messages(client: ZulipClient, narrow: Narrow): GetMessagesResponse =
        getMessages(client, narrow)

    override suspend fun topics(client: ZulipClient, streamId: Int): StreamTopicsResponse =
        getStreamTopics(client, streamId)

    override suspend fun upload(client: ZulipClient, attachment: PendingUploadAttachment): UploadFileResponse =
        uploadFile(
            client = client,
            fileName = attachment.displayName,
            mimeType = attachment.mimeType,
            bytes = attachment.file.readBytes(),
        )

    override suspend fun send(client: ZulipClient, narrow: Narrow, content: String): SendMessageResponse =
        sendMessage(client, narrow, content)
}
