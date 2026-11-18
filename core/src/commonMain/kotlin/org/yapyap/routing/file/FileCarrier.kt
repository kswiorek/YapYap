package org.yapyap.routing.file

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId

/** Account-level file events for the orchestrator (design §12): devices never cross the seam. */
internal sealed interface FileEvent {
    /** Push progress on one (file, target account) send. */
    data class OutgoingProgress(
        val fileId: FileId,
        val target: AccountId,
        val sentBytes: Long,
        val totalBytes: Long,
    ) : FileEvent

    /** Receive progress on one file (contiguous prefix P). */
    data class IncomingProgress(
        val fileId: FileId,
        val contiguousBytes: Long,
        val totalBytes: Long,
    ) : FileEvent

    /** A received file completed and verified; the permanent store holds it. */
    data class Ready(val fileId: FileId) : FileEvent

    /** A send or receive ended without delivery (never auto-retried by the receiver). */
    data class Failed(
        val fileId: FileId,
        val target: AccountId?,
        val reason: FileFailureReason,
    ) : FileEvent
}

internal enum class FileFailureReason {
    DECLINED,
    NO_SPACE,
    EXPIRED,
    CANCELLED,
    FAILED,
}

/**
 * Account-level file operations and events (design §12), declared by routing and implemented
 * by the router. The orchestrator speaks files and accounts; routing fans out per device and
 * signals per transfer. Source devices may appear in events as facts, never as addresses.
 */
internal interface FileCarrier {
    /** Hot event flow: progress, readiness and terminal failures, account-level only. */
    val fileEvents: Flow<FileEvent>

    /** Start pushing [fileId] to every device of [target] (small path; a large fetch is pull-only). */
    suspend fun pushFile(fileId: FileId, target: AccountId)

    /** End our push of [fileId] to [target]'s devices (user cancel). */
    suspend fun cancelFile(fileId: FileId, target: AccountId)

    /**
     * Our receive of [fileId] is READY: send COMPLETE to every live transfer author (from the
     * incoming-transfer rows), drop those rows, and emit [FileEvent.Ready].
     */
    suspend fun fileReady(fileId: FileId)

    /** Contribute an event to [fileEvents] (receive-side progress and failures). */
    suspend fun emit(event: FileEvent)
}
