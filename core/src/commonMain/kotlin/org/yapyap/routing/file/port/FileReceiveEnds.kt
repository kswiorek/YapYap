package org.yapyap.routing.file.port

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.FileCancelReason

/**
 * Receives the orchestrator's receive engine ended, which routing answers to every live transfer
 * author: COMPLETE for a ready file, CANCEL for a failed one. Declared by routing, implemented by
 * the engine; routing collects [receiveEnds] from its start, so the engine never calls routing.
 */
internal interface FileReceiveEnds {
    /** Buffered until collected: an end reported before routing starts is still answered. */
    val receiveEnds: Flow<FileReceiveEnd>
}

internal sealed interface FileReceiveEnd {
    val fileId: FileId

    /** [fileId] is READY: send COMPLETE to its transfer authors and drop their incoming rows. */
    data class Ready(override val fileId: FileId) : FileReceiveEnd

    /** The receive of [fileId] stopped: send CANCEL with [reason] and drop the incoming rows. */
    data class Cancelled(override val fileId: FileId, val reason: FileCancelReason) : FileReceiveEnd
}
