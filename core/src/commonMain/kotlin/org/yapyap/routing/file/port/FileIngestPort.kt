package org.yapyap.routing.file.port

import org.yapyap.protocol.FileId

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
 * Result of handing one fetch frame's bytes to the receive engine (design §6.6, §8).
 * Fetch frames arrive in order and append at P without staging. A failed write or verify throws,
 * leaving P where it was; `offset` other than the uncommitted end throws [IllegalArgumentException].
 */
internal sealed interface FileAppendResult {
    /** P after this call (often unchanged: checkpoints move it by bytes and by time). */
    data class Appended(val committedBytes: Long) : FileAppendResult

    /** The file is complete: this append finished it (READY follows on the ends flow), or it already was. */
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

    /** The chunk completed the file: checkpointed at completion, READY follows on the ends flow. */
    data class Ready(val contiguousBytes: Long, val totalBytes: Long) : FileIngestResult

    /** Fully-covered range (a duplicate across transfers/relays): ACK, nothing stored. */
    data class Duplicate(val contiguousBytes: Long, val totalBytes: Long) : FileIngestResult

    /** Staging bound hit or a transient failure: silent (no ACK, no NACK, dedup cleared); the sender retries. */
    data object Defer : FileIngestResult

    /** No space or otherwise refused: answered DECLINED; the sender ends quietly. */
    data object Refused : FileIngestResult
}

/**
 * Synchronous ingest port into the orchestrator's receive engine (design §8), declared by routing
 * and implemented by the engine. The handoff is synchronous so "ACK = decrypted and durably
 * recorded" holds: nothing is ACKed before it is handled. A transient failure (the store or disk
 * unavailable) answers `Defer`, never a refusal: the sender retries.
 */
internal interface FileIngestPort {
    suspend fun checkOffer(offer: FileManifest): FileOfferVerdict
    suspend fun ingest(
        fileId: FileId,
        byteOffset: Long,
        plaintext: ByteArray,
    ): FileIngestResult

    /**
     * Append fetch bytes at the uncommitted end (design §6.6, §8): straight into the `.part` at
     * [offset], without staging. [offset] must equal the uncommitted end, or the checkpointed
     * prefix when resuming after a drop (the leftover suffix is truncated). Checkpoints move P by
     * bytes (here) and by time (in the sweep).
     */
    suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult
}
