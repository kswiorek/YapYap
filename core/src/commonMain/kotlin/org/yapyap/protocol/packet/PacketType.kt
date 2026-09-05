package org.yapyap.protocol.packet

/**
 * Packet categories carried by the Tor envelope.
 */
enum class PacketType(val wireValue: Byte) {
    MESSAGE(1),
    SIGNAL(2),
    FILE(3),
    SYSTEM(4),
    BOOTSTRAP(5);

    companion object {
        fun fromWireValue(value: Byte): PacketType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported packet type value: $value")
    }
}

