package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.data.FileOutboxFileStore
import com.jhellerstedt.zulip.data.OutboxFileStore
import com.jhellerstedt.zulip.data.OutboxMessageState
import com.jhellerstedt.zulip.data.PendingAttachmentEntity
import com.jhellerstedt.zulip.data.PendingMessageEntity
import com.jhellerstedt.zulip.data.PendingOutboxItem
import com.jhellerstedt.zulip.data.ZulipOutboxDao
import com.jhellerstedt.zulip.data.ZulipOutboxRepository
import com.jhellerstedt.zulip.store.Narrow
import com.jhellerstedt.zulip.store.PendingUploadAttachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZulipOutboxRepositoryTest {
    private val accountId = 7L
    private val now = 1_000_000L

    @Test
    fun enqueueCopiesAttachmentsBeforeInsertingRows() = runTest {
        val dao = FakeOutboxDao()
        val root = temporaryDirectory()
        val source = root.resolve("source.jpg").apply { writeText("photo") }
        val repository = repository(dao, root, RecordingFileStore(root, events = dao.events))

        val result = repository.enqueue(
            accountId,
            Narrow.Channel(1),
            "caption",
            listOf(PendingUploadAttachment("photo.jpg", "image/jpeg", source)),
        )

        assertEquals("message-1", result.getOrThrow())
        assertEquals(listOf("copy", "insert"), dao.events)
        val attachment = dao.attachments.single()
        assertEquals("photo", File(attachment.filePath).readText())
        assertTrue(File(attachment.filePath).path.startsWith(root.resolve("zulip-outbox/$accountId/message-1").path))
    }

    @Test
    fun midCopyFailureRemovesPartialFileAndLeavesNoDaoRows() = runTest {
        val dao = FakeOutboxDao()
        val root = temporaryDirectory()
        val first = root.resolve("first.jpg").apply { writeText("first") }
        val second = root.resolve("second.jpg").apply { writeText("second") }
        var copyCalls = 0
        val files = FileOutboxFileStore(root) { source, target ->
            copyCalls += 1
            if (copyCalls == 2) {
                target.outputStream().use { it.write("partial".toByteArray()) }
                throw IOException("copy failed mid-way")
            }
            source.copyTo(target, overwrite = true)
        }
        val repository = repository(dao, root, files)

        val result = repository.enqueue(
            accountId,
            Narrow.Channel(1),
            "caption",
            listOf(
                PendingUploadAttachment("first.jpg", "image/jpeg", first),
                PendingUploadAttachment("second.jpg", "image/jpeg", second),
            ),
        )

        assertIs<IOException>(result.exceptionOrNull())
        assertTrue(dao.messages.isEmpty())
        assertTrue(dao.attachments.isEmpty())
        assertNoOutboxFiles(root)
    }

    @Test
    fun failedCopyCleansUpCopiedFilesAndDoesNotInsertRows() = runTest {
        val dao = FakeOutboxDao()
        val root = temporaryDirectory()
        val first = root.resolve("first.jpg").apply { writeText("first") }
        val second = root.resolve("second.jpg").apply { writeText("second") }
        val files = RecordingFileStore(root, failOnCopy = 2)
        val repository = repository(dao, root, files)

        val result = repository.enqueue(
            accountId,
            Narrow.Channel(1),
            "caption",
            listOf(
                PendingUploadAttachment("first.jpg", "image/jpeg", first),
                PendingUploadAttachment("second.jpg", "image/jpeg", second),
            ),
        )

        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertTrue(dao.messages.isEmpty())
        assertTrue(dao.attachments.isEmpty())
        assertTrue(files.copiedFiles.all { !it.exists() })
    }

    @Test
    fun queuedRowsAndCopiedFilesSurviveRepositoryRecreation() = runTest {
        val dao = FakeOutboxDao()
        val root = temporaryDirectory()
        val source = root.resolve("source.jpg").apply { writeText("photo") }
        val files = RecordingFileStore(root)
        val firstRepository = repository(dao, root, files)

        firstRepository.enqueue(
            accountId,
            Narrow.Topic(1, "release"),
            "caption",
            listOf(PendingUploadAttachment("photo.jpg", "image/jpeg", source)),
        ).getOrThrow()

        val recreatedRepository = repository(dao, root, files)
        val claimed = recreatedRepository.claimNext(accountId)

        assertEquals("message-1", claimed?.message?.localId)
        assertTrue(File(claimed!!.attachments.single().filePath).exists())
    }

    @Test
    fun checkpointUploadPreservesReturnedUrl() = runTest {
        val dao = FakeOutboxDao()
        val root = temporaryDirectory()
        val source = root.resolve("source.jpg").apply { writeText("photo") }
        val repository = repository(dao, root)
        repository.enqueue(
            accountId,
            Narrow.Channel(1),
            "caption",
            listOf(PendingUploadAttachment("photo.jpg", "image/jpeg", source)),
        ).getOrThrow()

        repository.checkpointUpload("attachment-2", "https://zulip.example/file")

        assertEquals("https://zulip.example/file", dao.attachments.single().uploadedUrl)
    }

    @Test
    fun completeDiscardAndDeleteAccountRemoveOwnedFiles() = runTest {
        val dao = FakeOutboxDao()
        val root = temporaryDirectory()
        val files = RecordingFileStore(root)
        val repository = repository(dao, root, files)
        val first = enqueueAttachment(repository, root, "one.jpg")
        val completeItem = repository.claimNext(accountId)!!

        repository.complete(completeItem)

        assertFalse(first.exists())
        assertTrue(dao.messages.isEmpty())

        val second = enqueueAttachment(repository, root, "two.jpg")
        repository.discard("message-3")
        assertFalse(second.exists())

        val third = enqueueAttachment(repository, root, "three.jpg")
        repository.deleteAccount(accountId)
        assertFalse(third.exists())
        assertTrue(dao.messages.isEmpty())
    }

    @Test
    fun retryChangesFailedMessageToQueued() = runTest {
        val dao = FakeOutboxDao()
        val root = temporaryDirectory()
        val repository = repository(dao, root)
        val file = enqueueAttachment(repository, root, "photo.jpg")
        repository.fail("message-1", "invalid recipient")

        repository.retry("message-1")

        assertEquals(OutboxMessageState.QUEUED, dao.messages.single().state)
        assertNull(dao.messages.single().failureReason)
        assertTrue(file.exists())
    }

    @Test
    fun resetInterruptedChangesSendingRowsToQueued() = runTest {
        val dao = FakeOutboxDao()
        val root = temporaryDirectory()
        val repository = repository(dao, root)
        enqueueAttachment(repository, root, "photo.jpg")
        repository.claimNext(accountId)

        repository.resetInterrupted(accountId)

        assertEquals(OutboxMessageState.QUEUED, dao.messages.single().state)
    }

    @Test
    fun summaryCountsPendingRowsAndUsesOldestFailure() = runTest {
        val dao = FakeOutboxDao()
        val root = temporaryDirectory()
        val repository = repository(dao, root)
        enqueueAttachment(repository, root, "one.jpg")
        enqueueAttachment(repository, root, "two.jpg")
        repository.fail("message-1", "first")
        repository.claimNext(accountId)
        dao.messages += PendingMessageEntity(
            localId = "older-failure",
            accountId = accountId,
            narrowKey = "stream:1:",
            content = "old",
            state = OutboxMessageState.FAILED,
            createdAt = now - 1,
            attemptAt = null,
            failureReason = "oldest",
        )
        dao.refresh()

        val summary = repository.observeSummary(accountId).first()

        assertEquals(1, summary.pendingCount)
        assertEquals("older-failure", summary.firstFailure?.localId)
    }

    private suspend fun enqueueAttachment(
        repository: ZulipOutboxRepository,
        root: File,
        name: String,
    ): File {
        val source = root.resolve("source-$name").apply { writeText(name) }
        repository.enqueue(
            accountId,
            Narrow.Channel(1),
            "caption",
            listOf(PendingUploadAttachment(name, "image/jpeg", source)),
        ).getOrThrow()
        val item = repository.claimNext(accountId) ?: error("missing item")
        repository.requeue(item.message.localId)
        return File(item.attachments.single().filePath)
    }

    private fun repository(
        dao: FakeOutboxDao,
        root: File,
        files: OutboxFileStore = RecordingFileStore(root),
    ): ZulipOutboxRepository {
        var nextId = 0
        return ZulipOutboxRepository(
            dao = dao,
            files = files,
            newId = { if (nextId++ % 2 == 0) "message-${nextId}" else "attachment-${nextId}" },
            nowMillis = { now },
        )
    }

    private fun temporaryDirectory(): File = Files.createTempDirectory("zulip-outbox-test").toFile()

    private fun assertNoOutboxFiles(root: File) {
        val outboxRoot = root.resolve("zulip-outbox")
        if (!outboxRoot.exists()) {
            return
        }
        assertTrue(
            outboxRoot.walkTopDown().none { it.isFile },
            "expected no files under ${outboxRoot.path}, found orphaned outbox files",
        )
    }
}

private class RecordingFileStore(
    private val root: File,
    private val failOnCopy: Int? = null,
    private val events: MutableList<String>? = null,
) : OutboxFileStore {
    val copiedFiles = mutableListOf<File>()
    private var copyCalls = 0

    override fun copyIn(accountId: Long, messageId: String, attachmentId: String, source: File): File {
        copyCalls += 1
        check(copyCalls != failOnCopy) { "copy failed" }
        events?.add("copy")
        val target = root.resolve("zulip-outbox/$accountId/$messageId/$attachmentId-${source.name}")
        target.parentFile!!.mkdirs()
        source.copyTo(target)
        copiedFiles += target
        return target
    }

    override fun delete(file: File) {
        file.delete()
    }

    override fun deleteAccount(accountId: Long) {
        root.resolve("zulip-outbox/$accountId").deleteRecursively()
    }
}

private class FakeOutboxDao : ZulipOutboxDao {
    val events = mutableListOf<String>()
    val messages = mutableListOf<PendingMessageEntity>()
    val attachments = mutableListOf<PendingAttachmentEntity>()
    private val changes = MutableStateFlow(0)

    override suspend fun insertMessage(message: PendingMessageEntity) {
        events += "insert"
        messages += message
        refresh()
    }

    override suspend fun insertAttachments(attachments: List<PendingAttachmentEntity>) {
        this.attachments += attachments
        refresh()
    }

    override fun pendingCount(accountId: Long): Flow<Int> =
        changes.map {
            messages.count { it.accountId == accountId && it.state in setOf(OutboxMessageState.QUEUED, OutboxMessageState.SENDING) }
        }

    override fun firstFailedMessage(accountId: Long): Flow<PendingMessageEntity?> =
        changes.map {
            messages.filter { it.accountId == accountId && it.state == OutboxMessageState.FAILED }
                .minByOrNull(PendingMessageEntity::createdAt)
        }

    override suspend fun oldestQueuedMessage(accountId: Long): PendingMessageEntity? =
        messages.filter { it.accountId == accountId && it.state == OutboxMessageState.QUEUED }
            .minByOrNull(PendingMessageEntity::createdAt)

    override suspend fun attachments(accountId: Long, messageLocalId: String): List<PendingAttachmentEntity> =
        attachments.filter { it.accountId == accountId && it.messageLocalId == messageLocalId }
            .sortedBy(PendingAttachmentEntity::sourceIndex)

    override suspend fun markSending(accountId: Long, messageLocalId: String, attemptAt: Long) {
        updateMessage(accountId, messageLocalId) { it.copy(state = OutboxMessageState.SENDING, attemptAt = attemptAt) }
    }

    override suspend fun markQueued(accountId: Long, messageLocalId: String) {
        updateMessage(accountId, messageLocalId) { it.copy(state = OutboxMessageState.QUEUED, failureReason = null) }
    }

    override suspend fun markFailed(accountId: Long, messageLocalId: String, failureReason: String) {
        updateMessage(accountId, messageLocalId) {
            it.copy(state = OutboxMessageState.FAILED, failureReason = failureReason)
        }
    }

    override suspend fun checkpointUpload(accountId: Long, attachmentLocalId: String, uploadedUrl: String) {
        val index = attachments.indexOfFirst { it.accountId == accountId && it.localId == attachmentLocalId }
        if (index >= 0) {
            attachments[index] = attachments[index].copy(uploadedUrl = uploadedUrl)
            refresh()
        }
    }

    override suspend fun deleteMessage(accountId: Long, messageLocalId: String) {
        messages.removeAll { it.accountId == accountId && it.localId == messageLocalId }
        attachments.removeAll { it.accountId == accountId && it.messageLocalId == messageLocalId }
        refresh()
    }

    override suspend fun message(messageLocalId: String): PendingMessageEntity? =
        messages.firstOrNull { it.localId == messageLocalId }

    override suspend fun attachment(attachmentLocalId: String): PendingAttachmentEntity? =
        attachments.firstOrNull { it.localId == attachmentLocalId }

    override suspend fun resetSending(accountId: Long) {
        messages.replaceAll {
            if (it.accountId == accountId && it.state == OutboxMessageState.SENDING) {
                it.copy(state = OutboxMessageState.QUEUED)
            } else {
                it
            }
        }
        refresh()
    }

    override suspend fun deleteMessagesForAccount(accountId: Long) {
        messages.removeAll { it.accountId == accountId }
        attachments.removeAll { it.accountId == accountId }
        refresh()
    }

    fun refresh() {
        changes.value += 1
    }

    private fun updateMessage(
        accountId: Long,
        messageId: String,
        update: (PendingMessageEntity) -> PendingMessageEntity,
    ) {
        val index = messages.indexOfFirst { it.accountId == accountId && it.localId == messageId }
        if (index >= 0) {
            messages[index] = update(messages[index])
            refresh()
        }
    }
}
