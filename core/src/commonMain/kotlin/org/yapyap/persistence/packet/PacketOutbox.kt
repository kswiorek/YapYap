package org.yapyap.persistence.packet

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import kotlin.uuid.Uuid

interface PacketOutbox {
    suspend fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Long, relayMessage: Boolean = false)
    suspend fun markDelivered(packetId: Uuid)
    suspend fun setDueForTarget(target: PeerId, nextRetryAt: Long)
    suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Long, now: Long)
    suspend fun listAllForTarget(target: PeerId): List<OutboxEntry>

    suspend fun listDue(now: Long): List<OutboxEntry>
    suspend fun pruneExpired(now: Long): Int
    suspend fun earliestPendingRetryAt(): Long?
    suspend fun relayCacheBytes(): Long
    suspend fun pruneRelayOverCapacity(maxBytes: Long): Int
}

data class OutboxEntry(
    val packetId: Uuid,
    val envelope: BinaryEnvelope,
    val nextRetryAt: Long?,
    val attempts: Long
)
