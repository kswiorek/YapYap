package org.yapyap.routing.file.port

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.ByteRange
import org.yapyap.protocol.envelopes.FileCancelReason

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
     * We started serving [fileId] to [requester]'s account (serve-on-REQUEST or a large-file serve).
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
     * A large-file candidate is online but no WebRTC session could be made
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

/**
 * What a file is, without its bytes or keys: the fields a seal and PULL_OK
 * carry. Routing builds one from an opened seal for the receiver's check, and the plaintext source
 * describes a held file with one for the sender.
 */
internal data class FileManifest(
    val fileId: FileId,
    val totalBytes: Long,
    val blockSize: Int,
    val blockHashes: List<ByteArray>,
    val fileName: String,
    val mimeType: String?,
)

/** Durable held coverage of one file: the contiguous prefix in bytes plus the missing ranges beyond it. */
internal data class FileCoverage(
    val contiguousBytes: Long,
    val missingRanges: List<ByteRange>,
)
