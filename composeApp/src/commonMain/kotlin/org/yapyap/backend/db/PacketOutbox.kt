package org.yapyap.backend.db

import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketId

interface PacketOutbox {
    fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Long? = null)
    fun markDelivered(packetId: PacketId)
    fun recordAttempt(packetId: PacketId, nextRetryAt: Long? = null)

    fun listDue(now: Long): List<BinaryEnvelope>
    fun pruneExpired(now: Long)
}