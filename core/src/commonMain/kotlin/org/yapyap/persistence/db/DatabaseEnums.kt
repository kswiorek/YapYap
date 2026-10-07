package org.yapyap.persistence.db

enum class IdentityStatus {
    ACTIVE,
    BANNED,
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
