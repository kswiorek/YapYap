package org.yapyap.persistence.packet

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.PacketNackReason
import kotlin.uuid.Uuid

interface PacketDeduplicator {
    /**
     * Marks packet as seen and returns whether it is first time seen.
     */
    suspend fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean

    suspend fun clearPacket(packetId: Uuid, sourceDeviceId: PeerId)

    suspend fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason)

    suspend fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason?

    suspend fun prune(receivedBeforeEpochSeconds: Long)
}