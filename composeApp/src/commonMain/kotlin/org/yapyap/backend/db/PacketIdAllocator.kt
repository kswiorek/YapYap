package org.yapyap.backend.db

import org.yapyap.backend.protocol.PacketId

interface PacketIdAllocator {
    /**
     * Allocates a packet ID unique for local device by reserving it in persistence.
     */
    fun assignLocalDevice(deviceId: String)
    fun allocate(): PacketId
}