package org.yapyap.persistence.packet

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.PacketNackReason
import kotlin.uuid.Uuid

interface PacketDeduplicator {
    /**
     * Marks packet as seen and returns whether it is first time seen.
     */
    fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean

    fun clearPacket(packetId: Uuid, sourceDeviceId: PeerId)

    fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason)

    fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason?

    fun prune(receivedBeforeEpochSeconds: Long)
}