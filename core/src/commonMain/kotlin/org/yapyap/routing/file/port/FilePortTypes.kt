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

/** Verdict for an OFFER: accept, short-circuit (already held), refuse, or wait. */
internal sealed interface FileOfferVerdict {
    data object Accept : FileOfferVerdict

    /**
     * This device already holds the file: the OFFER is ACKed, gets no row, and is
     * answered with COMPLETE — never DECLINED (a refusal would read as "won't serve").
     */
    data object AlreadyReady : FileOfferVerdict

    data class Refuse(val reason: FileRefusalReason) : FileOfferVerdict

    /** Prerequisites missing — silent (no ACK, no NACK, dedup cleared). */
    data object Defer : FileOfferVerdict
}

internal enum class FileRefusalReason {
    NO_SPACE,

    /** The block list does not hash to the fileId (the sender's copy is treated as bad). */
    INVALID_OFFER,

    /** Terminal states (FAILED/DECLINED/CANCELLED/EVICTED) are never auto-resurrected. */
    NOT_RETRYABLE,
}

/**
 * Result of handing one large-file DATA frame's bytes to the receive engine (design §6.6, §8).
 * DATA frames arrive in order and append at P without staging. A failed write or verify throws,
 * leaving P where it was; `offset` other than the uncommitted end throws [IllegalArgumentException].
 */
internal sealed interface FileAppendResult {
    /** P after this call (often unchanged: checkpoints move it by bytes and by time). */
    data class Appended(val committedBytes: Long) : FileAppendResult

    /** The file is complete: this append finished it (the engine reports READY on its own flow; the orchestrator then ends the incoming transfers), or it already was. */
    data object Ready : FileAppendResult

    /** A block failed its hash: truncated to its start; the requester ABORTs and re-asks. */
    data class Mismatch(val blockIndex: Int) : FileAppendResult

    /** The disk is full: the receive is DECLINED locally; the requester ABORTs and the request ends. */
    data object NoSpace : FileAppendResult

    /** The receive ended locally (declined, failed, forgotten): the requester stops, nothing to blame. */
    data object Ended : FileAppendResult

    /** Recovery is not done yet: retry the same bytes shortly. */
    data object Defer : FileAppendResult
}

/** Result of handing one decrypted chunk to the receive engine (synchronous, design §8). */
internal sealed interface FileIngestResult {
    /** Decrypted and durably recorded: the chunk may now be ACKed. Carries the new P. */
    data class Staged(val contiguousBytes: Long, val totalBytes: Long) : FileIngestResult

    /** The chunk completed the file: checkpointed at completion (READY is reported on the engine's own flow; the orchestrator then ends the incoming transfers). */
    data class Ready(val contiguousBytes: Long, val totalBytes: Long) : FileIngestResult

    /** Fully-covered range (a duplicate across transfers/relays): ACK, nothing stored. */
    data class Duplicate(val contiguousBytes: Long, val totalBytes: Long) : FileIngestResult

    /** Staging bound hit or a transient failure: silent (no ACK, no NACK, dedup cleared); the sender retries. */
    data object Defer : FileIngestResult

    /** No space or otherwise refused: answered DECLINED; the sender ends quietly. */
    data object Refused : FileIngestResult
}

/** One bounded, verified read from a [PlaintextStream]. */
internal sealed interface PlaintextRead {
    /** Verified bytes (never empty; EOF is separate). */
    data class Bytes(val bytes: ByteArray) : PlaintextRead {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Bytes
            if (!bytes.contentEquals(other.bytes)) return false
            return true
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** End of file: every byte was verified. */
    data object Eof : PlaintextRead

    /** The block at [blockIndex] failed verification: the serve ends here. */
    data class SourceLost(val blockIndex: Int) : PlaintextRead
}
