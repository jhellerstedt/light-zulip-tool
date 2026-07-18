package com.jhellerstedt.zulip.store

import com.jhellerstedt.zulip.ZulipServices
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext

private const val OUTBOX_JOB_KEY = "zulip-outbox"
private const val ACCOUNT_ID_INPUT = "account_id"

fun outboxWorkName(accountId: Long): String = "$OUTBOX_JOB_KEY-$accountId"

interface OutboxScheduler {
    fun enqueue(accountId: Long): Boolean
    fun cancel(accountId: Long)
}

class LightOutboxScheduler(
    private val context: SealedLightContext,
) : OutboxScheduler {
    override fun enqueue(accountId: Long): Boolean = LightWork.enqueue(
        lightContext = context,
        jobKey = OUTBOX_JOB_KEY,
        inputData = mapOf(ACCOUNT_ID_INPUT to accountId.toString()),
        tag = outboxWorkName(accountId),
    )

    override fun cancel(accountId: Long) {
        LightWork.cancel(context, outboxWorkName(accountId))
    }
}

suspend fun runOutboxJob(
    input: Map<String, String>,
    processor: suspend (Long) -> OutboxRunResult,
): LightJobResult {
    val accountId = input[ACCOUNT_ID_INPUT]?.toLongOrNull() ?: return LightJobResult.Error()
    return when (processor(accountId)) {
        OutboxRunResult.Success -> LightJobResult.Success()
        OutboxRunResult.Retry -> LightJobResult.Retry
    }
}

@LightJob(OUTBOX_JOB_KEY)
val zulipOutboxJob: LightJobHandler = { context, input ->
    runOutboxJob(input) { accountId ->
        ZulipServices.outboxProcessor(context).process(accountId)
    }
}
