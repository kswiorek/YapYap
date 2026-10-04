package org.yapyap.orchestrator.runtime.room

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId

/**
 * GUI-facing chat management: room list, room headers, room creation, and room
 * membership ops. Creation and membership changes publish real room-DAG events
 * (genesis + MemberAdd/MemberRemove/AddAdmin/RemoveAdmin) via the room-event
 * projector's publish path; the fold (not the service) decides what lands.
 */
interface RoomService {
    /**
     * Rooms the local account belongs to (GLOBAL excluded). Projection order —
     * message-derived ordering (e.g. by last activity) is composed by the GUI
     * from `MessagingService.roomPreview`, which owns all message reads.
     */
    val rooms: StateFlow<List<RoomSummary>>

    /** Chat-header detail, or null for unknown rooms. */
    suspend fun room(roomId: RoomId): RoomDetails?

    /**
     * Create a direct or group chat with [members]. Publishes the `RoomCreated`
     * genesis (the creator authors it and becomes OWNER by definition; the
     * member list seeds the room) and broadcasts it to the folded member set —
     * discovery is push, then frontier sync takes over. The creator is always
     * included in the genesis list. Refusals are values; infra failures throw.
     */
    suspend fun createRoom(name: String?, members: Set<AccountId>): CreateRoomResult

    /**
     * Membership ops (admin-gated unless noted). The service pre-checks against
     * our own projection for UX (refusals are values); the fold ignores invalid
     * events regardless, so a revocation race degrades to a no-op publish, never
     * a fork. Infra failures throw.
     */
    suspend fun addMember(roomId: RoomId, target: AccountId): RoomEventOutcome
    suspend fun removeMember(roomId: RoomId, target: AccountId): RoomEventOutcome
    suspend fun grantAdmin(roomId: RoomId, target: AccountId): RoomEventOutcome
    suspend fun revokeAdmin(roomId: RoomId, target: AccountId): RoomEventOutcome

    /**
     * Own-account leave (always allowed for non-owners). The owner's own leave
     * is the handover: it requires [successorAccountId] (an ACTIVE member that
     * is not the leaver) and atomically transfers OWNER + admin to them.
     */
    suspend fun leaveRoom(roomId: RoomId, successorAccountId: AccountId? = null): RoomEventOutcome
}
