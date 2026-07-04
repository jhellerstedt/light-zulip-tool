package com.thelightphone.zulip.store

import com.thelightphone.zulip.api.routes.getEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

class EventPoller(
    private val scope: CoroutineScope,
) {
    private var pollerJob: Job? = null
    private var backoffAttempt = 0

    fun start(store: ZulipStore) {
        stop()
        pollerJob = scope.launch {
            pollLoop(store)
        }
    }

    fun stop() {
        pollerJob?.cancel()
        pollerJob = null
        backoffAttempt = 0
    }

    private suspend fun pollLoop(store: ZulipStore) {
        val job = currentCoroutineContext()[Job]
        while (job?.isActive != false) {
            try {
                val response = getEvents(
                    client = store.client,
                    queueId = store.queueId,
                    lastEventId = store.lastEventId,
                    eventRequestTimeoutMillis = store.eventRequestTimeoutMillis,
                    dontBlock = backoffAttempt > 0,
                )
                response.events.forEach { event ->
                    store.handleEvent(event)
                }
                backoffAttempt = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                store.setError(error.message ?: "Failed to sync events")
                backoffAttempt = min(backoffAttempt + 1, 6)
                delay(backoffDelayMs(backoffAttempt))
            }
        }
    }

    private fun backoffDelayMs(attempt: Int): Long =
        min(1_000L * (1 shl attempt), 30_000L)
}
