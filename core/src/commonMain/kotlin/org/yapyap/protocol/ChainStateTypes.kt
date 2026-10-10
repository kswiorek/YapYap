package org.yapyap.protocol

public enum class IdentityStatus {
    ACTIVE,
    BANNED,
}

/**
 * Chain-derived network-wide account role (the `accounts.role` column, owned by
 * the global-event fold). OWNER is the network's repair path: irrevocable by
 * others, transferable only by its own handover. OWNER implies admin authority
 * everywhere `is_admin` was read — see [isAdmin].
 */
public enum class AccountRole {
    MEMBER,
    ADMIN,
    OWNER,
    ;

    /** OWNER implies admin authority (mirrors `RoomMemberRole` OWNER semantics). */
    public val isAdmin: Boolean get() = this != MEMBER
}

public enum class RoomMemberRole {
    ADMIN,
    MEMBER,
    OWNER,
}

public enum class RoomMemberStatus {
    ACTIVE,
    REMOVED,
}