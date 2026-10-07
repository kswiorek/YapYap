package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CoroutineScope
import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.RoomId
import kotlin.uuid.Uuid

interface SyncCoordinator {
    fun start(scope: CoroutineScope)
    suspend fun stop()

    /**
     * A ping carried [tips] as the peer's chainable frontier for [roomId].
     * Every tip unknown locally gets its own pending sync (one row per missing ID);
     * the responder serves the tip plus its ancestry down to our known frontier.
     *
     * [senderAccount] is the pinger's account (null when their device is
     * unknown). For an unknown room it becomes the candidate (the pinger's
     * assertion that we are in the room — docs/room events.md §6); for a known
     * room it is appended to the re-triggered rows' candidates (insert-if-absent,
     * so the sender is always a candidate — universal accumulation).
     */
    suspend fun requestFrontierSync(roomId: RoomId, tips: List<Uuid>, senderAccount: AccountId? = null)

    /**
     * Membership refresh for [roomId]: appends the room's current ACTIVE
     * members (own devices included — they are valid sync candidates) to all
     * of its pending sync rows' candidates
     * (insert-if-absent). Wired to the projectors' state-change flows plus a
     * boot sweep — candidates frozen at row-mint time would otherwise never
     * learn about later-added members (docs/room events.md §6). Append-only,
     * never replace: ping-sender and author candidates contributed by other
     * triggers survive.
     */
    suspend fun refreshCandidatesFor(roomId: RoomId)
}