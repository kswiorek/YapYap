package org.yapyap.protocol

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * Shared protocol vocabulary: endpoint identities and wire enums. Everything encoded on
 * the wire or referenced by every layer lives here, so no layer imports sideways or upward
 * for these types.
 */

// ------------------------------------------------------------------
// Identities
// ------------------------------------------------------------------

data class PeerId(
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "PeerId cannot be blank" }
    }
}

/**
 * Wire-level room identifier. Encoded in envelopes, so it lives here rather than in
 * the DAG — protocol and routing must not import the orchestrator for it.
 */
@JvmInline
value class RoomId(val value: Uuid) {
    companion object {
        /** The single global control room shared by every device. */
        val GLOBAL = RoomId(Uuid.NIL)
    }
}

data class TorEndpoint(
    val onionAddress: String,
    val port: Int = 80,
) {
    init {
        require(onionAddress.endsWith(".onion")) { "onionAddress must end with .onion" }
        require(port in 1..65535) { "port must be in range 1..65535" }
    }
}

// ------------------------------------------------------------------
// Wire enums
// ------------------------------------------------------------------

enum class SignalSecurityScheme(val wireValue: Byte, val nonceSize: Int) {
    PLAINTEXT_TEST_ONLY(0, 24),
    SIGNED(1, 24),
    ENCRYPTED_AND_SIGNED(2, 24);

    companion object {
        fun fromWireValue(value: Byte): SignalSecurityScheme =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported signal security scheme wire value: $value")
    }
}

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

enum class DeviceType(val wireValue: Byte) {
    APPLE(0),
    ANDROID(1),
    DESKTOP(2),
    HEADLESS(3);

    companion object {
        fun fromWireValue(value: Byte): DeviceType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported device type wire value: $value")
    }
}

enum class RoomType(val wireValue: Byte) {
    TEXT_CHANNEL(0),
    VOICE_CHANNEL(1),
    GLOBAL_CONTROL(2),

    /**
     * Local-only provisional marker for rooms we hold messages for but whose
     * genesis has not folded yet. Never on the wire (`RoomCreated` rejects it);
     * the room projector overwrites it on genesis commit; the GUI filters it.
     */
    UNKNOWN(3);

    companion object {
        fun fromWireValue(value: Byte): RoomType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported room type wire value: $value")
    }
}

enum class MessagePayloadType(val wireValue: Byte) {
    TEXT(1),
    GLOBAL_EVENT(2),
    ROOM_EVENT(3);

    companion object {
        fun fromWireValue(value: Byte): MessagePayloadType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported message payload type wire value: $value")
    }
}
