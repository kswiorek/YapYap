package org.yapyap.routing.file

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.FileCancelReason

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

    /**
     * A REQUEST of ours was satisfied: the holder's OFFER arrived and its transfer
     * takes over. The pending request is dropped.
     */
    data class RequestSatisfied(val fileId: FileId) : FileEvent

    /** Every candidate declined (or expired without serving): terminal until a user re-opens. */
    data class RequestUnavailable(val fileId: FileId) : FileEvent

    /**
     * We started serving [fileId] to [requester]'s account (serve-on-REQUEST): the
     * orchestrator records the SERVE row. Follows [OutgoingProgress] for accounting.
     */
    data class ServeStarted(
        val fileId: FileId,
        val requester: AccountId,
    ) : FileEvent

    /** A send or serve transfer completed end-to-end (COMPLETE from the target). */
    data class OutgoingComplete(
        val fileId: FileId,
        val target: AccountId,
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

    /**
     * Our receive of [fileId] failed cleanly (hash limit, ENOSPC): send CANCEL to every live
     * transfer author and drop those rows so senders stop into a quiet end.
     */
    suspend fun cancelIncoming(fileId: FileId, reason: FileCancelReason)

    /** Contribute an event to [fileEvents] (receive-side progress and failures). */
    suspend fun emit(event: FileEvent)
}
