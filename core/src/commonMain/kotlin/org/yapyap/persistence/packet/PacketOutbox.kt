package org.yapyap.persistence.packet

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface PacketOutbox {
    suspend fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Instant, relayMessage: Boolean = false)
    suspend fun markDelivered(packetId: Uuid)
    suspend fun setDueForTarget(target: PeerId, nextRetryAt: Instant)
    suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Instant, at: Instant)
    suspend fun listAllForTarget(target: PeerId): List<OutboxEntry>

    suspend fun listDue(now: Instant): List<OutboxEntry>
    suspend fun pruneExpired(now: Instant): Int
    suspend fun earliestPendingRetryAt(): Instant?
    suspend fun relayCacheBytes(): Long
    suspend fun pruneRelayOverCapacity(maxBytes: Long): Int
}

data class OutboxEntry(
    val packetId: Uuid,
    val envelope: BinaryEnvelope,
    val nextRetryAt: Instant?,
    val attempts: Long
)