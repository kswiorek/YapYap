package org.yapyap.routing.file

import org.yapyap.protocol.FileId

/**
 * What the receiver knows about an OFFER before accepting it: the public half plus the
 * opened sealed half. The key stays routing-side (it lands in the incoming-transfer row).
 */
internal data class FileOfferInfo(
    val fileId: FileId,
    val totalBytes: Long,
    val blockSize: Int,
    val blockHashes: List<ByteArray>,
    val fileName: String,
    val mimeType: String?,
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
}

/** Result of handing one decrypted chunk to the receive engine (synchronous, design §8). */
internal sealed interface FileIngestResult {
    /** Decrypted and durably recorded: the chunk may now be ACKed. Carries the new P. */
    data class Staged(val contiguousBytes: Long, val totalBytes: Long) : FileIngestResult

    /** Fully-covered range (a duplicate across transfers/relays): ACK, nothing stored. */
    data object Duplicate : FileIngestResult

    /** Staging bound hit: silent (no ACK, no NACK, dedup cleared); the sender retries. */
    data object Defer : FileIngestResult

    /** No space or otherwise refused: answered DECLINED; the sender ends quietly. */
    data object Refused : FileIngestResult
}

/**
 * Synchronous ingest port into the orchestrator's receive engine (design §8), declared by
 * routing and implemented in `orchestrator/file` (step 12). The handoff is synchronous so
 * "ACK = decrypted and durably recorded" holds: nothing is ACKed before handling.
 */
internal interface FileIngestPort {
    suspend fun checkOffer(offer: FileOfferInfo): FileOfferVerdict
    suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray): FileIngestResult
}
