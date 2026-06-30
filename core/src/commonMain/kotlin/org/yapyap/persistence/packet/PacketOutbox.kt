package org.yapyap.persistence.packet

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.PeerId

interface PacketOutbox {
    fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Long, relayMessage: Boolean = false)
    fun markDelivered(packetId: PacketId)
    fun setDueForTarget(target: PeerId, nextRetryAt: Long)
    fun recordAttempt(packetId: PacketId, nextRetryAt: Long, now: Long)
    fun listAllForTarget(target: PeerId): List<OutboxEntry>

    fun listDue(now: Long): List<OutboxEntry>
    fun pruneExpired(now: Long): Int
    fun earliestPendingRetryAt(): Long?
    fun relayCacheBytes(): Long
    fun pruneRelayOverCapacity(maxBytes: Long): Int
}

data class OutboxEntry(
    val packetId: PacketId,
    val envelope: BinaryEnvelope,
    val nextRetryAt: Long?,
    val attempts: Long
)
