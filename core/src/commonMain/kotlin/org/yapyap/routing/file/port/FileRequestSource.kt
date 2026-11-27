package org.yapyap.routing.file.port

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId

/**
 * What the request loop and the serve path need to know about a file (design §3.2, §9).
 * Declared by routing, implemented by the orchestrator's request coordinator.
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

    /**
     * Serve gate (design §9): true when [requester] may see a message referencing [fileId] — the
     * mirror of [requestCandidates], the same visibility rule sync's serving uses. Without it,
     * anyone who has or guesses a file's content could pull it from any holder by its hash.
     */
    suspend fun canServe(fileId: FileId, requester: AccountId): Boolean
}
