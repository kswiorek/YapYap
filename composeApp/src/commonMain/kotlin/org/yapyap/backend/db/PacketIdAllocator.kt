package org.yapyap.backend.db

import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PeerId

interface PacketIdAllocator {
    /**
     * Allocates a packet ID unique for local device by reserving it in persistence.
     */
    fun assignLocalDevice(deviceId: PeerId)
    fun allocate(): PacketId
}