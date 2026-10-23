package com.jhellerstedt.zulip.store

import com.jhellerstedt.zulip.api.ZulipGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

class EventPoller(
    private val scope: CoroutineScope,
    private val gateway: ZulipGateway,
    private val coordinator: ZulipSyncCoordinator,
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

    val isRunning: Boolean
        get() = pollerJob?.isActive == true

    private suspend fun pollLoop(store: ZulipStore) {
        val job = currentCoroutineContext()[Job]
        while (job?.isActive != false) {
            try {
                val response = gateway.events(
                    client = store.client,
                    queueId = store.queueId,
                    lastEventId = store.lastEventId,
                    timeoutMillis = store.eventRequestTimeoutMillis,
                    dontBlock = backoffAttempt > 0,
                )
                response.events.forEach { event ->
                    coordinator.applyEvent(store, event)
                }
                backoffAttempt = 0
                store.setConnectionState(ConnectionState.ONLINE)
                store.clearError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                when (classifyEventPollFailure(error)) {
                    EventPollFailure.QueueLost -> if (!recoverQueue(store)) return
                    EventPollFailure.Transient -> backoff(store)
                    EventPollFailure.Stopped -> {
                        store.setConnectionState(ConnectionState.OFFLINE)
                        store.setError(error.message ?: "Failed to sync events")
                        return
                    }
                }
            }
        }
    }

    private suspend fun recoverQueue(store: ZulipStore): Boolean {
        val registerResult = coordinator.register(store)
        if (registerResult.isSuccess) {
            store.clearError()
            backoffAttempt = 0
            return true
        }
        val registerError = registerResult.exceptionOrNull()
        return when (registerError?.let(::classifyEventPollFailure)) {
            EventPollFailure.Transient, null -> {
                backoff(store)
                true
            }
            EventPollFailure.QueueLost,
            EventPollFailure.Stopped,
            -> {
                store.setConnectionState(ConnectionState.OFFLINE)
                store.setError(registerError.message ?: "Failed to sync events")
                false
            }
        }
    }

    private suspend fun backoff(store: ZulipStore) {
        store.setConnectionState(ConnectionState.OFFLINE)
        backoffAttempt = min(backoffAttempt + 1, 6)
        delay(backoffDelayMs(backoffAttempt))
    }

    private fun backoffDelayMs(attempt: Int): Long =
        min(1_000L * (1 shl attempt), 30_000L)
}
