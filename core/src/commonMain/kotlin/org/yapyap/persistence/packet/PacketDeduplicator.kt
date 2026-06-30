package org.yapyap.persistence.packet

import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.PeerId

interface PacketDeduplicator {
    /**
     * Marks packet as seen and returns whether it is first time seen.
     */
    fun firstSeen(packetId: PacketId, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean

    fun markNacked(packetId: PacketId, sourceDeviceId: PeerId, nackReason: PacketNackReason)

    fun getNackReason(packetId: PacketId, sourceDeviceId: PeerId): PacketNackReason?

    fun prune(receivedBeforeEpochSeconds: Long)
}