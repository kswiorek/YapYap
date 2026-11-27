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
import org.yapyap.protocol.MAX_FILE_NAME_BYTES
import org.yapyap.protocol.MAX_FILE_NAME_CHARS
import org.yapyap.protocol.MAX_MIME_TYPE_BYTES
import org.yapyap.protocol.MAX_MIME_TYPE_CHARS
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.routing.file.port.FileCarrier

/**
 * Owns file-send import, classification and starting pushes (design §10, §12).
 * `DefaultMessagingService` delegates to it instead of touching `FileStore` or `FileCarrier`.
 *
 * Every imported file is pinned ([FilePins]) from import until [confirm] or [release], so quota
 * never evicts it meanwhile. Descriptors a prepare created stay releasable until [confirm]: a
 * refused, failed or cancelled prepare releases its own, and [release] drops them when the DAG
 * append fails. Only the last unpin of a file deletes it, so a concurrent send of the same file
 * keeps it.
 */
//TODO:[File cleanup]: rename, services are in runtime
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
    suspend fun release(attachments: List<DagPayload.Attachment>)

    /** The DAG append succeeded: [attachments] are now referenced, not releasable. */
    suspend fun confirm(attachments: List<DagPayload.Attachment>)

    /** Start per-device pushes of [attachments] to [members]; a large file just no-ops (pull-only). */
    suspend fun startPushes(attachments: List<DagPayload.Attachment>, members: Collection<AccountId>)

    /** Delete partial copies left by sends a previous run killed mid-hash. */
    suspend fun cleanTmp()
}

internal sealed interface PrepareResult {
    data class Ready(val attachments: List<DagPayload.Attachment>) : PrepareResult
    data class Refused(val refusal: SendRefusal) : PrepareResult
}

internal class DefaultFileSendService(
    private val fileStore: FileStore,
    private val quota: FileQuota,
    private val pins: FilePins,
    private val descriptors: FileDescriptorReader,
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
        val attachments = mutableListOf<DagPayload.Attachment>()
        var ready = false
        try {
            for (picked in files) { //TODO [File cleanup]: async loop? Breaks if one of multiple is refused, return those whih are fine, the rest can error out in gui
                val descriptor = when (val pinned = importPinned(picked) { bytes ->
                    hashedBytes += bytes
                    onProgress(hashedBytes, totalBytes)
                }) {
                    is Pinned.Held -> pinned.descriptor
                    is Pinned.Refused -> return PrepareResult.Refused(pinned.refusal)
                }
                attachments += DagPayload.Attachment(
                    fileId = descriptor.fileId,
                    fileName = descriptor.fileName,
                    mimeType = descriptor.mimeType,
                    totalBytes = descriptor.totalBytes,
                )
            }
            // Pinned files (this send's and every other one in flight) are never evicted.
            quota.enforceQuota()
            ready = true
            return PrepareResult.Ready(attachments)
        } finally {
            if (!ready) withContext(NonCancellable) { release(attachments) }
        }
    }

    private sealed interface Pinned {
        data class Held(val descriptor: FileDescriptor) : Pinned
        data class Refused(val refusal: SendRefusal) : Pinned
    }

    /**
     * Imports [picked] and pins it. A file another send already held may have been deleted by
     * that send's release between our import and our pin: it is imported again then.
     */
    private suspend fun importPinned(picked: PickedFile, onBytes: (Long) -> Unit): Pinned {
        repeat(2) {
            val imported = fileStore.import(
                source = importSource(picked),
                fileName = displayFileName(picked.fileName),
                mimeType = displayMimeType(picked.mimeType),
                keepCopy = picked.keepCopy,
                onBytes = onBytes,
            )
            val descriptor = when (imported) {
                ImportResult.SourceUnreadable -> return Pinned.Refused(SendRefusal.FileUnreadable)
                ImportResult.NoSpace -> return Pinned.Refused(SendRefusal.NoSpace)
                is ImportResult.Imported -> imported.descriptor
            }
            pins.pin(descriptor.fileId)
            if (imported.created) {
                releasableGuard.withLock { releasable[descriptor.fileId] = descriptor.storage }
                return Pinned.Held(descriptor)
            }
            if (descriptors.findById(descriptor.fileId) != null) return Pinned.Held(descriptor)
            pins.unpin(descriptor.fileId)
        }
        return Pinned.Refused(SendRefusal.FileUnreadable)
    }

    override suspend fun release(attachments: List<DagPayload.Attachment>) {
        for (attachment in attachments) {
            pins.unpin(attachment.fileId) {
                val storage = releasableGuard.withLock { releasable.remove(attachment.fileId) } ?: return@unpin
                // A message may already reference the same bytes (an earlier send of this file).
                if (messages.findMessageIdsByFileId(attachment.fileId).isNotEmpty()) return@unpin
                fileStore.forget(attachment.fileId)
                if (storage == FileStorage.COPY) files.delete(Path(storeDirectory.toString(), attachment.fileId.value))
            }
        }
    }

    override suspend fun confirm(attachments: List<DagPayload.Attachment>) {
        for ((fileId) in attachments) {
            releasableGuard.withLock { releasable.remove(fileId) }
            pins.unpin(fileId)
        }
    }

    /** A push that fails to start is logged: the receiver's pull path still fetches the file. */
    override suspend fun startPushes(
        attachments: List<DagPayload.Attachment>,
        members: Collection<AccountId>,
    ) {
        for (attachment in attachments) { //TODO [File cleanup] Async loops
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

    internal companion object {
        /**
         * The name as it travels in the attachment (design §3): trimmed to the shared caps
         * ([MAX_FILE_NAME_CHARS] characters, [MAX_FILE_NAME_BYTES] UTF-8 bytes) before the import
         * hashes anything, keeping the extension where it fits. A blank name becomes "file".
         */
        fun displayFileName(name: String): String {
            val trimmed = name.trim().ifEmpty { "file" }
            if (fits(trimmed, MAX_FILE_NAME_CHARS, MAX_FILE_NAME_BYTES)) return trimmed
            val dot = trimmed.lastIndexOf('.')
            val ext = if (dot > 0 && trimmed.length - dot <= 16) trimmed.substring(dot) else ""
            var stem = trimmed.substring(0, trimmed.length - ext.length)
            while (stem.isNotEmpty() && !fits(stem + ext, MAX_FILE_NAME_CHARS, MAX_FILE_NAME_BYTES)) {
                stem = stem.dropLast(1)
                // Never leave half of a surrogate pair at the end.
                if (stem.isNotEmpty() && stem.last().isHighSurrogate()) stem = stem.dropLast(1)
            }
            return (stem.trimEnd() + ext).ifBlank { "file" }
        }

        /** A MIME type that would fail the attachment's caps (blank, too long) is dropped. */
        fun displayMimeType(mimeType: String?): String? =
            mimeType?.trim()?.takeIf { it.isNotEmpty() && fits(it, MAX_MIME_TYPE_CHARS, MAX_MIME_TYPE_BYTES) }

        private fun fits(value: String, maxChars: Int, maxBytes: Int): Boolean =
            value.length <= maxChars && value.encodeToByteArray().size <= maxBytes
    }

    private fun importSource(picked: PickedFile): FileImportSource = when (val s = picked.source) {
        is PickedFileSource.FilePath -> FileImportSource.PathSource(Path(s.path))
        is PickedFileSource.ContentUri -> FileImportSource.ContentUriSource(s.uri)
    }
}
