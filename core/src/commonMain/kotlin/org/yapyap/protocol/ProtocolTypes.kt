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

public data class PeerId(
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
public value class RoomId(public val value: Uuid) {
    public companion object {
        /** The single global control room shared by every device. */
        public val GLOBAL: RoomId = RoomId(Uuid.NIL)
    }
}

/** A DAG message, content-addressed by its author at creation and the same on every device. */
@JvmInline
public value class MessageId(public val value: Uuid) : Comparable<MessageId> {
    override fun compareTo(other: MessageId): Int = value.compareTo(other.value)
}

/** One sync: a request for a missing DAG message and its ancestry, tracked until it arrives. */
@JvmInline
public value class SyncId(public val value: Uuid)

public data class TorEndpoint(
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

/**
 * Packet families: the type selects how a [org.yapyap.protocol.envelopes.BinaryEnvelope]'s routing
 * header and content are read.
 */
internal enum class PacketType(val wireValue: Byte) {
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

public enum class DeviceType(public val wireValue: Byte) {
    APPLE(0),
    ANDROID(1),
    DESKTOP(2),
    HEADLESS(3);

    internal companion object {
        fun fromWireValue(value: Byte): DeviceType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported device type wire value: $value")
    }
}

public enum class RoomType(public val wireValue: Byte) {
    TEXT_CHANNEL(0),
    VOICE_CHANNEL(1),
    GLOBAL_CONTROL(2),

    /**
     * Local-only provisional marker for rooms we hold messages for but whose
     * genesis has not folded yet. Never on the wire (`RoomCreated` rejects it);
     * the room projector overwrites it on genesis commit; the GUI filters it.
     */
    UNKNOWN(3);

    internal companion object {
        fun fromWireValue(value: Byte): RoomType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported room type wire value: $value")
    }
}

/**
 * Which family of payload a message envelope carries — the first byte of every message payload.
 * The family byte selects the decoder; each family then reads its own type byte
 * ([DagPayloadType] for [DAG], [SyncPayloadType] for [SYNC]), so the two vocabularies never mix.
 */
public enum class MessagePayloadFamily(public val wireValue: Byte) {
    DAG(1),
    SYNC(2);

    internal companion object {
        fun fromWireValue(value: Byte): MessagePayloadFamily =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported message payload family: $value")
    }
}

/** The DAG node types — the second byte of a [MessagePayloadFamily.DAG] payload. */
public enum class DagPayloadType(public val wireValue: Byte) {
    TEXT(1),
    GLOBAL_EVENT(2),
    ROOM_EVENT(3);

    internal companion object {
        fun fromWireValue(value: Byte): DagPayloadType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported DAG payload type wire value: $value")
    }
}

/**
 * The pull-path control types — the second byte of a [MessagePayloadFamily.SYNC] payload.
 * One value today; the byte is reserved so further sync verbs can be added without a new family.
 */
public enum class SyncPayloadType(public val wireValue: Byte) {
    REQUEST(1);

    internal companion object {
        fun fromWireValue(value: Byte): SyncPayloadType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported sync payload type wire value: $value")
    }
}

public data class AccountId(
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "AccountId cannot be blank" }
    }
}