package org.yapyap.backend.db

import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketNackReason
import org.yapyap.backend.protocol.PeerId

interface PacketDeduplicator {
    /**
     * Marks packet as seen and returns whether it is first time seen.
     */
    fun firstSeen(packetId: PacketId, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean

    fun markNacked(packetId: PacketId, sourceDeviceId: PeerId, nackReason: PacketNackReason)

    fun getNackReason(packetId: PacketId, sourceDeviceId: PeerId): PacketNackReason?

    /**
     * Deletes dedup markers older than [receivedBeforeEpochSeconds].
     */
    fun prune(receivedBeforeEpochSeconds: Long)
}