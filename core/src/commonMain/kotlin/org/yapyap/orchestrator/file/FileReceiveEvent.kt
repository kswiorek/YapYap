package org.yapyap.orchestrator.file

import org.yapyap.protocol.FileId
import org.yapyap.routing.file.port.FileFailureReason

/** What the receive engine reports about a file it receives, on its own flow. */
internal sealed interface FileReceiveEvent {
    val fileId: FileId

    /** The contiguous prefix of [fileId] grew to [contiguousBytes] of [totalBytes]. */
    data class Progress(
        override val fileId: FileId,
        val contiguousBytes: Long,
        val totalBytes: Long,
    ) : FileReceiveEvent

    /** [fileId] completed and verified; the permanent store holds it. */
    data class Ready(override val fileId: FileId) : FileReceiveEvent

    /** The receive of [fileId] failed for [reason] and is never retried automatically. */
    data class Failed(
        override val fileId: FileId,
        val reason: FileFailureReason,
    ) : FileReceiveEvent
}
