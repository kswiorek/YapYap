package org.yapyap.routing.file.port

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.ByteRange

/**
 * What the request loop needs to ask for a file (design §3.2, §9). Declared by routing,
 * implemented by the orchestrator's request coordinator.
 */
internal interface FileRequestSource {
    /**
     * This device's durable held coverage for building a REQUEST: the contiguous prefix plus the
     * capped missing ranges beyond it (heavy fragmentation degrades to contiguous-only, a coarser
     * serve that is still correct). Null when no request should be built: the file is already
     * READY, and the loop drops its row.
     */
    suspend fun heldCoverage(fileId: FileId): FileCoverage?

    /**
     * Accounts that may hold [fileId], senders first: the accounts whose messages reference the
     * file, then our own account (sibling devices). Routing maps them to devices and filters by
     * link, like sync.
     */
    suspend fun requestCandidates(fileId: FileId): List<AccountId>

    /**
     * Total bytes of [fileId] as known from its message attachment or receive row, or null when
     * unknown. Picks the small (outbox REQUEST) or large (session and fetch) path; the limit is the
     * network value `SMALL_FILE_MAX_BYTES` (design §1).
     */
    suspend fun requestTotalBytes(fileId: FileId): Long?

}

/** Durable held coverage of one file: the contiguous prefix in bytes plus the missing ranges beyond it. */
internal data class FileCoverage(
    val contiguousBytes: Long,
    val missingRanges: List<ByteRange>,
)
