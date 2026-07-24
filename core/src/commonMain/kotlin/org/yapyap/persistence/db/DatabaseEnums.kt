package org.yapyap.persistence.db

enum class AccountStatus {
    ACTIVE,
    BANNED,
    UNBOUND,
}

enum class DeviceType {
    APPLE,
    ANDROID,
    DESKTOP,
    HEADLESS,
}

enum class RoomType {
    TEXT_CHANNEL,
    VOICE_CHANNEL,
    GLOBAL_CONTROL,
}

enum class RoomMemberRole {
    ADMIN,
    MEMBER,
}

enum class MessagePayloadType(val wireValue: Byte) {
    TEXT(1),
    GLOBAL_EVENT(2);

    companion object {
        fun fromWireValue(value: Byte): MessagePayloadType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported message payload type wire value: $value")
    }
}

enum class MessageLifecycleState {
    CREATED,
    SENT,
    ACKED,
    ARCHIVED,
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
