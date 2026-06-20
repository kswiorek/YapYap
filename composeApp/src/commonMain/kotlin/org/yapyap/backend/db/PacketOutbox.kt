package org.yapyap.backend.db

import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PeerId

interface PacketOutbox {
    fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Long? = null)
    fun markDelivered(packetId: PacketId)
    fun setDueForTarget(target: PeerId, nextRetryAt: Long)
    fun recordAttempt(packetId: PacketId, nextRetryAt: Long? = null)
    fun listAllForTarget(target: PeerId): List<OutboxEntry>

    fun listDue(now: Long): List<OutboxEntry>
    fun pruneExpired(now: Long)
    fun earliestPendingRetryAt(): Long?
}

data class OutboxEntry(
    val packetId: PacketId,
    val envelope: BinaryEnvelope,
    val nextRetryAt: Long?,
    val attempts: Int
)