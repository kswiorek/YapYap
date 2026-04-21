package org.yapyap.backend.protocol

/**
 * Packet categories carried by the Tor envelope.
 */
enum class PacketType(val wireValue: Byte) {
    MESSAGE(1),
    ACK(2),
    DISCOVERY(3),
    SIGNAL(4);

    companion object {
        fun fromWireValue(value: Byte): PacketType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported packet type value: $value")
    }
}

