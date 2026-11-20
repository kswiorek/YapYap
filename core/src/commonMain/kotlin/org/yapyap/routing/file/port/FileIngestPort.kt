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

/** Result of handing one decrypted chunk to the receive engine (synchronous, design §8). */
internal sealed interface FileIngestResult {
    /** Decrypted and durably recorded: the chunk may now be ACKed. Carries the new P. */
    data class Staged(val contiguousBytes: Long, val totalBytes: Long) : FileIngestResult

    /** Fully-covered range (a duplicate across transfers/relays): ACK, nothing stored. */
    data object Duplicate : FileIngestResult

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
    suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray): FileIngestResult
}
