package org.yapyap.persistence.db

enum class IdentityStatus {
    ACTIVE,
    BANNED,
}

/**
 * Chain-derived network-wide account role (the `accounts.role` column, owned by
 * the global-event fold). OWNER is the network's repair path: irrevocable by
 * others, transferable only by its own handover. OWNER implies admin authority
 * everywhere `is_admin` was read — see [isAdmin].
 */
enum class AccountRole {
    MEMBER,
    ADMIN,
    OWNER,
    ;

    /** OWNER implies admin authority (mirrors `RoomMemberRole` OWNER semantics). */
    val isAdmin: Boolean get() = this != MEMBER
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
