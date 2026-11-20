package org.yapyap.orchestrator.runtime.file

import kotlinx.io.files.Path
import org.yapyap.orchestrator.file.FileDescriptorStatus
import org.yapyap.orchestrator.file.FileDescriptorStore
import org.yapyap.orchestrator.file.FileReceiveRow
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.FileReceiveStore
import org.yapyap.orchestrator.file.PendingFileRequestStore
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.Router
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

internal class DefaultFileTransferService(
    private val receives: FileReceiveStore,
    private val descriptors: FileDescriptorStore,
    private val files: FileAccess,
    private val pending: PendingFileRequestStore,
    private val messages: MessageRepository,
    private val router: Router,
    private val clock: Clock = Clock.System,
) : FileTransferService {
    override suspend fun receiveState(fileId: FileId): FileReceiveSnapshot? {
        val row = receives.find(fileId) ?: return null
        return FileReceiveSnapshot(
            fileId = row.fileId,
            status = row.status,
            contiguousBytes = row.contiguousBytes,
            totalBytes = row.totalBytes,
        )
    }

    override suspend fun localFilePath(fileId: FileId): Path? {
        val row = receives.find(fileId)
        if (row != null && row.status != FileReceiveStatus.READY) return null
        val descriptor = descriptors.findById(fileId) ?: return null
        if (descriptor.status != FileDescriptorStatus.HELD) return null
        val path = Path(descriptor.path)
        if (files.fileSize(path) == null) return null
        return path
    }

    override suspend fun acceptFile(fileId: FileId) {
        val now = clock.now()
        val row = runCatching { receives.find(fileId) }.getOrNull()
        if (row != null && row.status == FileReceiveStatus.READY) return
        // A local DECLINED (or FAILED/CANCELLED/EVICTED) row blocks the fetch's
        // checkOffer (never auto-resurrected): remove it so FETCH_OK can create
        // the IN_FLIGHT row, then start the request at once.
        if (row != null && row.status != FileReceiveStatus.IN_FLIGHT && row.status != FileReceiveStatus.STALLED) {
            runCatching { receives.delete(fileId) }
        }
        runCatching { pending.upsertPending(fileId, now, now) }
        runCatching { router.startFileRequest(fileId, now) }
    }

    override suspend fun declineFile(fileId: FileId) {
        val now = clock.now()
        runCatching { router.abortFileFetch(fileId) }
        runCatching { pending.drop(fileId) }
        val row = runCatching { receives.find(fileId) }.getOrNull()
        if (row != null) {
            if (row.status != FileReceiveStatus.READY) {
                runCatching { receives.updateStatus(fileId, FileReceiveStatus.DECLINED, now) }
            }
            return
        }
        // No row yet (large file above the auto-download limit the coordinator
        // never saw): record DECLINED from the message attachment so nothing
        // auto-fetches it later. Paths are placeholders — DECLINED rows are
        // inert (refused before any path is touched) until accept deletes them.
        val attachment = findAttachment(fileId) ?: return
        runCatching {
            receives.upsert(
                FileReceiveRow(
                    fileId = fileId,
                    status = FileReceiveStatus.DECLINED,
                    totalBytes = attachment.totalBytes,
                    contiguousBytes = 0L,
                    blockSize = null,
                    blockHashes = null,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                    hashMismatches = 0,
                    partPath = "",
                    finalPath = "",
                    originMessageId = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private suspend fun findAttachment(fileId: FileId): MessagePayload.Attachment? {
        val messageIds = try {
            messages.findMessageIdsByFileId(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        for (messageId in messageIds) {
            val stored = try {
                messages.findById(messageId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: continue
            val text = stored.payload as? MessagePayload.Text ?: continue
            text.attachments.firstOrNull { it.fileId == fileId }?.let { return it }
        }
        return null
    }
}
