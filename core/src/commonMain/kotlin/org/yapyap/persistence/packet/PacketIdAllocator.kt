package org.yapyap.persistence.packet

import org.yapyap.protocol.PeerId
import kotlin.uuid.Uuid

interface PacketIdAllocator {
    /**
     * Allocates a packet ID unique for local device by reserving it in persistence.
     */
    fun assignLocalDevice(deviceId: PeerId)
    fun allocate(now: Long): Uuid
}