package org.yapyap.routing.file

import org.yapyap.protocol.AccountId
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

    /** Staging bound hit: silent (no ACK, no NACK, dedup cleared); the sender retries. */
    data object Defer : FileIngestResult

    /** No space or otherwise refused: answered DECLINED; the sender ends quietly. */
    data object Refused : FileIngestResult
}

/**
 * Synchronous ingest port into the orchestrator's receive engine (design §8), declared by
 * routing and implemented in `orchestrator/file` (step 12). The handoff is synchronous so
 * "ACK = decrypted and durably recorded" holds: nothing is ACKed before handling.
 *
 * The request-side methods (coverage, candidates, serve gate) are bundled here rather than
 * in a new port: one seam for "everything the orchestrator knows about a file", implemented
 * by the file coordinator, which delegates the ingest side to the receive engine.
 */
internal interface FileIngestPort {
    suspend fun checkOffer(offer: FileOfferInfo): FileOfferVerdict
    suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray): FileIngestResult

    /**
     * This device's durable held coverage for building a REQUEST (design §3.2, §9):
     * the contiguous prefix plus the capped missing ranges beyond it (heavy
     * fragmentation degrades to contiguous-only — a coarser serve, still correct).
     * Null when no request should be built (already READY — the asker's loop drops
     * its row).
     */
    suspend fun heldCoverage(fileId: FileId): FileCoverage?

    /**
     * Candidate accounts that may hold [fileId], senders first: the accounts whose
     * messages reference the file, then our own account (sibling devices). Room
     * members later. Routing maps them to devices and link-filters like sync.
     */
    suspend fun requestCandidates(fileId: FileId): List<AccountId>

    /**
     * Serve gate (design §9, same rule as sync's): serve only when [requester] may see
     * a message referencing [fileId]. Without the gate, anyone who has or guesses a
     * file's content could pull it from any holder by its hash.
     */
    suspend fun canServe(fileId: FileId, requester: AccountId): Boolean

    /**
     * Total bytes of [fileId] as known from its message attachment (or receive row),
     * or null when unknown. Lets the request loop pick the small (outbox REQUEST) or
     * large (session + fetch) path — the limit is the network value
     * `SMALL_FILE_MAX_BYTES` (design §1).
     */
    suspend fun requestTotalBytes(fileId: FileId): Long?
}
