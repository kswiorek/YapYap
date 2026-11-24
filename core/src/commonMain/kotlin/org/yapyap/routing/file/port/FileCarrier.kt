package org.yapyap.routing.file.port

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileFetchAbortReason
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.FileCancelReason
import kotlin.time.Instant

/**
 * What routing knows about files, account-level for the orchestrator (design §12): sends, serves
 * and requests. Devices never cross the seam. Receive progress, readiness and receive failures
 * belong to the receive engine, which reports them itself.
 */
internal sealed interface FileEvent {
    /** Push progress on one (file, target account) send. */
    data class OutgoingProgress(
        val fileId: FileId,
        val target: AccountId,
        val sentBytes: Long,
        val totalBytes: Long,
    ) : FileEvent

    /** A send ended without delivery to [target]. */
    data class Failed(
        val fileId: FileId,
        val target: AccountId,
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
     * We started serving [fileId] to [requester]'s account (serve-on-REQUEST or a fetch).
     */
    data class ServeStarted(
        val fileId: FileId,
        val requester: AccountId,
    ) : FileEvent

    /**
     * Our send to [target] is handed off: what the target holds plus a relay's whole copy cover
     * the file, so relays deliver the rest without us. Not a delivery proof (docs/envelope-redesign.md §5.6).
     */
    data class OutgoingHandedOff(
        val fileId: FileId,
        val target: AccountId,
    ) : FileEvent

    /** Our send to [target] was delivered: the target holds the file. */
    data class OutgoingComplete(
        val fileId: FileId,
        val target: AccountId,
    ) : FileEvent

    /**
     * A large-fetch candidate is online but no WebRTC session could be made
     * (NAT, no direct connection — design §6.6). Distinct from offline: the
     * request stays PENDING (never DECLINED for this) and retries when a
     * candidate comes online. The GUI can show "can't connect directly".
     */
    data class NoDirectConnection(val fileId: FileId) : FileEvent
}

internal enum class FileFailureReason {
    /** The target refused the file, including for lack of space (design §8). */
    DECLINED,

    /** This device ran out of space. */
    NO_SPACE,

    /** The transfer lifetime ran out before delivery. */
    EXPIRED,

    /** The user cancelled. */
    CANCELLED,

    /** Every transfer ended without delivery or hand-off: the outcome at the target is unknown. */
    ENDED,

    /** This device could not go on (the file unreadable, verification failed). */
    FAILED,
}

/**
 * How a receive ended. The orchestrator (the receive engine's single lifecycle owner) reports it
 * through [FileCarrier.endIncoming]; routing owns the resulting signaling and row cleanup.
 */
internal sealed interface FileReceiveEnd {
    /** The file is complete and verified: tell the senders and the seals' origins. */
    data object Ready : FileReceiveEnd

    /** The receive stopped: send CANCEL with [reason] to every live incoming transfer's sender. */
    data class Cancelled(val reason: FileCancelReason) : FileReceiveEnd
}

/**
 * Account-level file operations and events (design §12), declared by routing and implemented
 * by the router. The orchestrator speaks files and accounts; routing fans out per device and
 * signals per transfer. Source devices may appear in events as facts, never as addresses.
 */
internal interface FileCarrier {
    /** Hot event flow: send progress and outcomes, serves and request outcomes, account-level only. */
    val fileEvents: Flow<FileEvent>

    /** Start pushing [fileId] to every device of [target] (small path; a large fetch is pull-only). */
    suspend fun pushFile(fileId: FileId, target: AccountId)

    /** End our push of [fileId] to [target]'s devices (user cancel). */
    suspend fun cancelFile(fileId: FileId, target: AccountId)

    /** Per-account send state, computed from our seals of [fileId] (no table of its own). */
    suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState>

    /**
     * Starts asking candidate devices for [fileId] (idempotent), first at [firstAttemptAt]. What to
     * ask for and which accounts may hold it come from the [FileRequestSource]; routing asks one
     * candidate at a time until a seal of the file (small) or FETCH_OK (large) arrives or every
     * candidate declines.
     */
    suspend fun startFileRequest(fileId: FileId, firstAttemptAt: Instant)

    /**
     * Aborts our live large-file fetch of [fileId] with [reason] (`STALLED` for the receiver gap
     * trigger, `CANCELLED` for the user), ABORTing and closing only the requester — never our
     * serves of the same file. Idempotent: unknown files are a no-op. The small path needs
     * nothing (its gap trigger cancels no transfer).
     */
    suspend fun abortFileFetch(fileId: FileId, reason: FileFetchAbortReason)

    /**
     * The receive of [fileId] ended ([end]). For [FileReceiveEnd.Ready] routing tells every live
     * incoming transfer's sender that it holds everything and sends each seal's origin a
     * COMPLETE; for [FileReceiveEnd.Cancelled] it CANCELs the live incoming transfers. The
     * incoming transfer rows go either way. Incoming only: our own sends and serves of the same
     * file are separate, ended by their own targets.
     *
     * File-scoped, never peer-scoped: the orchestrator never names devices. The answer is
     * politeness — an unanswered author's transfer ends on its own TTL — so this is idempotent
     * and may be called again for the same file.
     */
    suspend fun endIncoming(fileId: FileId, end: FileReceiveEnd)
}

/** Per-account send state for one file, aggregated over its device transfers. */
internal data class FileSendState(
    val status: FileSendStatus,
    val sentBytes: Long,
    val totalBytes: Long,
)

internal enum class FileSendStatus {
    SENDING,

    /** Relays hold the rest and deliver it; shown as sent, not as delivered. */
    HANDED_OFF,
    COMPLETED,
    ENDED,
    EXPIRED,
    DECLINED,
    CANCELLED,
}
