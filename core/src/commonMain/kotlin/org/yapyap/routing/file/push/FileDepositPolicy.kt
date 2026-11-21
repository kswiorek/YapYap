package org.yapyap.routing.file.push

import org.yapyap.protocol.TransferId
import org.yapyap.routing.store.FilePush
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStore
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Gone-only deposit triggers (design §6.3): the target's link closed, no link at claim time,
 * or no chunk ACK at all for `chunkAckTimeout` while rows are in flight. One slow or retried
 * chunk is never a signal. The round happens once per source transfer: `deposited_at` is the
 * claim (durable, restart-safe).
 */
internal class FileDepositPolicy(
    private val pushes: FilePushStore,
) {
    /**
     * The timeout rule: a transfer with rows in flight and no chunk ACK at all for [timeout]
     * deposits its remainder. Only the absence of every ACK counts; one slow or retried
     * chunk never triggers it.
     */
    fun shouldDeposit(
        row: FilePush,
        now: Instant,
        lastAckAt: Instant,
        hasQueued: Boolean,
        markerAcked: Boolean,
        timeout: Duration,
    ): Boolean {
        if (row.kind != FilePushKind.DIRECT && row.kind != FilePushKind.SERVE) return false
        if (!row.status.isLive) return false
        if (!markerAcked) return false
        if (!hasQueued) return false
        if (now >= row.expiresAt) return false
        return now - lastAckAt > timeout
    }

    /**
     * Claims the one deposit round for [transferId]. True exactly once per source transfer,
     * across restarts.
     */
    suspend fun claimOnce(transferId: TransferId, now: Instant): Boolean =
        pushes.claimDeposited(transferId, now)
}
