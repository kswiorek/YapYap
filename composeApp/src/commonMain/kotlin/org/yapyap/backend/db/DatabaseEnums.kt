package org.yapyap.backend.db

enum class AccountStatus {
    ACTIVE,
    BANNED,
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

enum class MessagePayloadType {
    TEXT,
    GLOBAL_EVENT,
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
