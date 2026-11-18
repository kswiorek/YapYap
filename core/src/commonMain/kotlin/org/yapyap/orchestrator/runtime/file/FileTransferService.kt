package org.yapyap.orchestrator.runtime.file

import kotlinx.io.files.Path
import org.yapyap.protocol.FileId

/**
 * GUI-facing receive state for one file: the receive engine's durable state, vended for
 * display (progress bars, "preparing" placeholders). What is *stored* (paths, bytes) only
 * ever leaves through [FileTransferService.localFilePath].
 */
public data class FileReceiveSnapshot(
    val fileId: FileId,
    val status: org.yapyap.orchestrator.file.FileReceiveStatus,
    val contiguousBytes: Long,
    val totalBytes: Long,
)

/**
 * GUI-facing file surface (design §12): the runtime stays the GUI's whole API. Sending
 * goes through [org.yapyap.orchestrator.runtime.message.MessagingService.sendMessage] with
 * picked files; this facade vends receive state and completed-file paths. Progress
 * reporting and the large-file accept/decline API land with the GUI surface (step 23).
 */
public interface FileTransferService {
    /** Durable receive state for [fileId], or null when this device never took part in it. */
    public suspend fun receiveState(fileId: FileId): FileReceiveSnapshot?

    /** Final store path when [fileId] is READY and held, else null. */
    public suspend fun localFilePath(fileId: FileId): Path?
}
