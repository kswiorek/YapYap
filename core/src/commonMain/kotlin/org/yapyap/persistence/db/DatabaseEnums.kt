package org.yapyap.persistence.db

enum class IdentityStatus {
    ACTIVE,
    BANNED,
}

enum class DeviceType {
    APPLE,
    ANDROID,
    DESKTOP,
    HEADLESS,
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

enum class RoomMemberRole {
    ADMIN,
    MEMBER,
    OWNER,
}

enum class RoomMemberStatus {
    ACTIVE,
    REMOVED,
}

enum class VerificationState {
    VERIFIED,
    PENDING,
    REJECTED,
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

enum class FileTransferStatus {
    IN_FLIGHT,
    PAUSED,
    COMPLETED,
    CANCELLED,
}

enum class FileChunkStatus {
    MISSING,
    REQUESTED,
    WRITTEN,
}

enum class OpkStatus {
    ALLOCATED,
    OFFERED,
    CONSUMED,
}
