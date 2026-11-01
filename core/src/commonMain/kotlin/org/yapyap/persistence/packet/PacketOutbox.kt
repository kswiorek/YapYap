package org.yapyap.persistence.packet

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal interface PacketOutbox {
    /**
     * @param targetEndpoint out-of-band endpoint override for targets with no local devices row
     *   (bootstrap: recovery request / intro). Persisted on the row so retries — including after
     *   a restart — resolve it without the DB. Null for regular peers (resolved via the devices
     *   table at dispatch).
     */
    suspend fun enqueue(
        envelope: BinaryEnvelope,
        nextRetryAt: Instant,
        relayMessage: Boolean = false,
        targetEndpoint: TorEndpoint? = null,
        relayBackup: RelayBackup = RelayBackup.NONE,
    )

    /**
     * Moves [packetId]'s relay backup from [RelayBackup.PENDING] to [RelayBackup.DEPOSITED];
     * true exactly once per row, for the caller that must now make the relay copies. False
     * when the row is gone (delivered, expired) or its backup is done or not applicable.
     */
    suspend fun claimRelayBackup(packetId: Uuid): Boolean

    suspend fun markDelivered(packetId: Uuid)
    suspend fun setDueForTarget(target: PeerId, nextRetryAt: Instant)
    suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Instant, at: Instant)

    /** Moves [packetId]'s next retry to [nextRetryAt] without counting an attempt. */
    suspend fun deferRetry(packetId: Uuid, nextRetryAt: Instant)
    suspend fun listAllForTarget(target: PeerId): List<OutboxEntry>

    suspend fun listDue(now: Instant): List<OutboxEntry>
    suspend fun pruneExpired(now: Instant): Int
    suspend fun earliestPendingRetryAt(): Instant?

    /** Earliest retry time strictly after [after], ignoring entries already due. */
    suspend fun earliestPendingRetryAfter(after: Instant): Instant?
    suspend fun relayCacheBytes(): Long
    suspend fun pruneRelayOverCapacity(maxBytes: Long): Int
}

/**
 * Store-and-forward backup of a message sent straight to its recipient: relays get copies only
 * when the direct path shows it may not deliver (no link, or no ACK in time), at most once.
 */
internal enum class RelayBackup(val code: Long) {
    /** Not a message to its final recipient (relay copies, system packets, bootstrap). */
    NONE(0),

    /** Copies not made yet; made once delivery looks doubtful. */
    PENDING(1),

    /** Copies made (or the message was already covered). */
    DEPOSITED(2),
}

internal data class OutboxEntry(
    val packetId: Uuid,
    val envelope: BinaryEnvelope,
    val nextRetryAt: Instant?,
    val attempts: Long,
    /** Out-of-band endpoint override, present exactly when the target has no devices row (bootstrap). */
    val targetEndpoint: TorEndpoint? = null,
)