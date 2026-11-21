package org.yapyap.orchestrator.file

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.file.receive.FileQuota
import org.yapyap.orchestrator.runtime.message.PickedFile
import org.yapyap.orchestrator.runtime.message.PickedFileSource
import org.yapyap.orchestrator.runtime.message.SendRefusal
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.port.FileCarrier

/**
 * Owns file-send import, classification and starting pushes (design §10, §12).
 * `DefaultMessagingService` delegates to it instead of touching `FileStore` or `FileCarrier`.
 *
 * Descriptors a prepare created stay releasable until [confirm]: a refused, failed or cancelled
 * prepare releases its own, and [release] drops them when the DAG append fails.
 */
internal interface FileSendService {
    /**
     * Imports [files] and returns their attachments, or the refusal. Never throws for unreadable
     * input or a full disk. On a refusal, an exception or cancellation, the descriptors this call
     * created are released before it returns.
     */
    suspend fun prepare(
        files: List<PickedFile>,
        onProgress: (hashedBytes: Long, totalBytes: Long) -> Unit,
    ): PrepareResult

    /** The DAG append failed: delete the descriptors (and copies) [attachments]' prepare created. */
    suspend fun release(attachments: List<MessagePayload.Attachment>)

    /** The DAG append succeeded: [attachments] are now referenced, not releasable. */
    suspend fun confirm(attachments: List<MessagePayload.Attachment>)

    /** Start per-device pushes for small files in [attachments] to [members]. */
    suspend fun startPushes(attachments: List<MessagePayload.Attachment>, members: Collection<AccountId>)

    /** Delete partial copies left by sends a previous run killed mid-hash. */
    suspend fun cleanTmp()
}

internal sealed interface PrepareResult {
    data class Ready(val attachments: List<MessagePayload.Attachment>) : PrepareResult
    data class Refused(val refusal: SendRefusal) : PrepareResult
}

internal class DefaultFileSendService(
    private val fileStore: FileStore,
    private val quota: FileQuota,
    private val files: FileAccess,
    private val storeDirectory: Path,
    private val fileCarrier: FileCarrier,
    private val messages: MessageRepository,
) : FileSendService {
    /** Descriptors a prepare created and no append has confirmed yet, with their storage. */
    private val releasable = mutableMapOf<FileId, FileStorage>()
    private val releasableGuard = Mutex()

    override suspend fun prepare(
        files: List<PickedFile>,
        onProgress: (hashedBytes: Long, totalBytes: Long) -> Unit,
    ): PrepareResult {
        val sizes = files.map { sourceSize(it) ?: return PrepareResult.Refused(SendRefusal.FileUnreadable) }
        val totalBytes = sizes.sum()
        var hashedBytes = 0L
        val attachments = mutableListOf<MessagePayload.Attachment>()
        var ready = false
        try {
            for (picked in files) {
                val imported = fileStore.import(
                    source = importSource(picked),
                    fileName = picked.fileName,
                    mimeType = picked.mimeType,
                    keepCopy = picked.keepCopy,
                    onBytes = { bytes ->
                        hashedBytes += bytes
                        onProgress(hashedBytes, totalBytes)
                    },
                )
                val descriptor = when (imported) {
                    ImportResult.SourceUnreadable -> return PrepareResult.Refused(SendRefusal.FileUnreadable)
                    ImportResult.NoSpace -> return PrepareResult.Refused(SendRefusal.NoSpace)
                    is ImportResult.Imported -> imported.descriptor.also {
                        if (imported.created) releasableGuard.withLock { releasable[it.fileId] = it.storage }
                    }
                }
                attachments += MessagePayload.Attachment(
                    fileId = descriptor.fileId,
                    fileName = descriptor.fileName,
                    mimeType = descriptor.mimeType,
                    totalBytes = descriptor.totalBytes,
                )
            }
            // No copy of a send still on its way to the DAG may go, this one's or another's.
            val keep = releasableGuard.withLock { releasable.keys.toSet() } + attachments.map { it.fileId }
            quota.enforceQuota(keep)
            ready = true
            return PrepareResult.Ready(attachments)
        } finally {
            if (!ready) withContext(NonCancellable) { release(attachments) }
        }
    }

    override suspend fun release(attachments: List<MessagePayload.Attachment>) {
        for (attachment in attachments) {
            val storage = releasableGuard.withLock { releasable.remove(attachment.fileId) } ?: continue
            // Another message may reference the same bytes (a concurrent send of the same file).
            if (messages.findMessageIdsByFileId(attachment.fileId).isNotEmpty()) continue
            fileStore.forget(attachment.fileId)
            if (storage == FileStorage.COPY) files.delete(Path(storeDirectory.toString(), attachment.fileId.value))
        }
    }

    override suspend fun confirm(attachments: List<MessagePayload.Attachment>) {
        releasableGuard.withLock { attachments.forEach { releasable.remove(it.fileId) } }
    }

    /** A push that fails to start is logged: the receiver's pull path still fetches the file. */
    override suspend fun startPushes(
        attachments: List<MessagePayload.Attachment>,
        members: Collection<AccountId>,
    ) {
        for (attachment in attachments) {
            if (attachment.totalBytes > SMALL_FILE_MAX_BYTES) continue
            for (member in members) {
                try {
                    fileCarrier.pushFile(attachment.fileId, member)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Failed to start push; the pull path covers it",
                        throwable = e,
                        fields = mapOf("fileId" to attachment.fileId),
                    )
                }
            }
        }
    }

    override suspend fun cleanTmp() = fileStore.cleanTmp()

    private suspend fun sourceSize(picked: PickedFile): Long? = when (val s = picked.source) {
        is PickedFileSource.FilePath -> files.fileSize(Path(s.path))
        is PickedFileSource.ContentUri -> files.contentSize(s.uri)
    }

    private fun importSource(picked: PickedFile): FileImportSource = when (val s = picked.source) {
        is PickedFileSource.FilePath -> FileImportSource.PathSource(Path(s.path))
        is PickedFileSource.ContentUri -> FileImportSource.ContentUriSource(s.uri)
    }
}
