package org.yapyap.persistence.packet

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.PacketNackReason
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface PacketDeduplicator {
    /**
     * Marks packet as seen and returns whether it is first time seen.
     */
    suspend fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAt: Instant): Boolean

    suspend fun clearPacket(packetId: Uuid, sourceDeviceId: PeerId)

    suspend fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason)

    suspend fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason?

    suspend fun prune(receivedBefore: Instant)
}