package org.yapyap.persistence.packet

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import kotlin.uuid.Uuid

interface PacketOutbox {
    fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Long, relayMessage: Boolean = false)
    fun markDelivered(packetId: Uuid)
    fun setDueForTarget(target: PeerId, nextRetryAt: Long)
    fun recordAttempt(packetId: Uuid, nextRetryAt: Long, now: Long)
    fun listAllForTarget(target: PeerId): List<OutboxEntry>

    fun listDue(now: Long): List<OutboxEntry>
    fun pruneExpired(now: Long): Int
    fun earliestPendingRetryAt(): Long?
    fun relayCacheBytes(): Long
    fun pruneRelayOverCapacity(maxBytes: Long): Int
}

data class OutboxEntry(
    val packetId: Uuid,
    val envelope: BinaryEnvelope,
    val nextRetryAt: Long?,
    val attempts: Long
)
