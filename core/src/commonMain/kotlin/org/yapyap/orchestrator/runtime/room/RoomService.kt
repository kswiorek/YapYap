package org.yapyap.orchestrator.runtime.room

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId

/**
 * GUI-facing chat management: room list, room headers, and room creation.
 * POC creation is local-only; the member-announcement protocol lands later
 * without changing this signature.
 */
interface RoomService {
    /** Rooms the local account belongs to (GLOBAL excluded), ordered by last activity. */
    val rooms: StateFlow<List<RoomSummary>>

    /** Chat-header detail, or null for unknown rooms. */
    suspend fun room(roomId: RoomId): RoomDetails?

    /**
     * Create a direct or group chat with [members]. The creator joins as ADMIN,
     * other members as MEMBER. Refusals are values; infra failures throw.
     */
    suspend fun createRoom(name: String?, members: Set<AccountId>): CreateRoomResult
}
