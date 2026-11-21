package org.yapyap.orchestrator.runtime.file

import kotlinx.io.files.Path
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.runtime.message.MessagingService
import org.yapyap.protocol.FileId

/**
 * GUI-facing receive state for one file: the receive engine's durable state, vended for
 * display (progress bars, "preparing" placeholders). What is *stored* (paths, bytes) only
 * ever leaves through [FileTransferService.localFilePath].
 */
public data class FileReceiveSnapshot(
    val fileId: FileId,
    val status: FileReceiveStatus,
    val contiguousBytes: Long,
    val totalBytes: Long,
)

/**
 * GUI-facing file surface (design §12): the runtime stays the GUI's whole API. Sending
 * goes through [MessagingService.sendMessage] with
 * picked files; this facade vends receive state, completed-file paths and the large-file
 * accept/decline decision. Progress reporting and open land with the GUI surface.
 */
public interface FileTransferService {
    /** Durable receive state for [fileId], or null when this device never took part in it. */
    public suspend fun receiveState(fileId: FileId): FileReceiveSnapshot?

    /** Final store path when [fileId] is READY and held, else null. */
    public suspend fun localFilePath(fileId: FileId): Path?

    /**
     * Accepts a large file this device declined (above the auto-download limit):
     * drops the local DECLINED row and starts the fetch. Re-openable: accepting an
     * already-held or in-flight file is a no-op beyond (re)starting the request.
     */
    public suspend fun acceptFile(fileId: FileId)

    /**
     * Declines [fileId] locally: aborts a live fetch, drops the pending request and
     * records DECLINED so nothing auto-fetches it later. Nothing goes on the wire.
     */
    public suspend fun declineFile(fileId: FileId)
}
