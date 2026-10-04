package org.yapyap.orchestrator.fold.room

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.protocol.envelopes.RoomEventPayload
import kotlin.uuid.Uuid

/**
 * Pure fold core of a chat room's membership DAG (see docs/room events.md).
 * No crypto oracles, no verdict output; the engine precomputes authenticity and the
 * adapter feeds the filtered order only. Same stored set folds identically everywhere.
 */

/** One fold input node. Null [event] rows (content messages) ride the order as no-ops:
 * they are needed for ancestry closures and canonical positions but never exercise
 * authority. */
data class RoomFoldNode(
    val id: Uuid,
    val authorAccountId: AccountId,
    val event: RoomEventPayload?,
)

/**
 * Genesis: the unique `VERIFIED` empty-`prevIds` `RoomCreated` node. Self-certifying
 * `roomId`s admit exactly one genesis per room, so no most-descendants resolution exists
 * here — the adapter resolves it defensively and the core recognizes it by [nodeId].
 * Its author becomes owner by definition; [ownerAccountId] is the adapter-resolved owner
 * (carried for call-site symmetry with the global `GenesisInfo`).
 */
internal data class RoomGenesisInfo(val nodeId: Uuid, val ownerAccountId: AccountId)

/**
 * Chain-derived member projection. Removal is a status, never a role.
 * [removalNodeId] is the defining (last honored) `MemberRemove` node — the
 * removal boundary for the bounded sync serve and the ping-contradiction
 * re-push; null while ACTIVE (re-add clears it).
 */
internal data class FoldRoomMember(
    val accountId: AccountId,
    val role: RoomMemberRole,
    val status: RoomMemberStatus,
    val removalNodeId: Uuid? = null,
)

/**
 * Shadow-state sets only (§4) — no verdict map is written (authenticity-only verdicts, §3).
 * The ever-validly-member set is [members.keys] (`REMOVED` rows are retained as the
 * projection's badge source). [ownerAccountId] is null only pre-genesis; the slot is never
 * empty once the genesis has folded.
 */
internal data class RoomFoldOutput(
    val members: Map<AccountId, FoldRoomMember>,
    val ownerAccountId: AccountId?,
)

internal data class RoomReplayResult(
    val output: RoomFoldOutput,
    /** First-in-canonical-order defining seal node per sealed account. */
    val seals: Map<AccountId, Uuid>,
)

/**
 * The revocation footprint of one walk — the restart loop's fixpoint key. One seal kind
 * only (no bans, no tombstones): `MemberRemove` and `RemoveAdmin` mint the same
 * interval-scoped seal.
 */
internal data class RoomRevocationState(
    val seals: Map<AccountId, Uuid>,
)

/**
 * Oscillation selection: most seals in effect wins (contested principal loses); ties break
 * by canonical node order. Deterministic, hence universal. Mirrors `revocationRank`.
 */
private val roomRevocationRank = compareBy<RoomReplayResult>(
    { it.seals.size },
    { it.seals.values.map { node -> node.toString() }.sorted().joinToString() },
)

/**
 * Restart loop over carried seals. A backdated event sorting before its revocation is
 * caught on the restart, where the revocation is known from the start; a repeated
 * seal-state means the walks cycle (mutual destruction), so the maximal-seal fixpoint is
 * kept. Pure function of the inputs — and non-suspend: the room tier has no crypto
 * oracles, so unlike the global driver this needs no coroutine context.
 */
internal fun roomFoldToFixpoint(
    order: List<Uuid>,
    nodes: Map<Uuid, RoomFoldNode>,
    ancestors: Map<Uuid, Set<Uuid>>,
    genesis: RoomGenesisInfo?,
    onOscillation: () -> Unit = {},
    onWalk: (walkIndex: Int, result: RoomReplayResult) -> Unit = { _, _ -> },
): RoomReplayResult {
    val history = LinkedHashMap<RoomRevocationState, RoomReplayResult>()
    var carried = RoomRevocationState(emptyMap())
    var current = replayRoomFold(order, nodes, ancestors, genesis, carried.seals)
    var walkIndex = 0
    onWalk(walkIndex, current)
    while (true) {
        val found = RoomRevocationState(current.seals)
        if (found == carried) break
        if (found in history) {
            current = (history.values + current).maxWithOrNull(roomRevocationRank) ?: current
            onOscillation()
            break
        }
        history[found] = current
        carried = found
        current = replayRoomFold(order, nodes, ancestors, genesis, carried.seals)
        walkIndex++
        onWalk(walkIndex, current)
    }
    return current
}

/**
 * One replay walk. Every id in [order] is replayed; null-`event` rows are no-ops and
 * bad shapes fail closed.
 *
 * Contract on [order]: the adapter-filtered fold set — `VERIFIED` ∧ stored-flag rows
 * (all payload types). The set is ancestor-closed because the flag requires every
 * ancestor `VERIFIED` (docs/room events.md §4, §11) — so there is no separate fold
 * set: closures built over the same rows lose nothing, and every id the walk queries
 * from [ancestors] is in [order].
 */
internal fun replayRoomFold(
    order: List<Uuid>,
    nodes: Map<Uuid, RoomFoldNode>,
    ancestors: Map<Uuid, Set<Uuid>>,
    genesis: RoomGenesisInfo?,
    carriedSeals: Map<AccountId, Uuid>,
): RoomReplayResult {
    val members = LinkedHashMap<AccountId, FoldRoomMember>()
    var owner: AccountId? = null
    val walkSeals = LinkedHashMap<AccountId, Uuid>()
    // Canonical positions, for the seal order gate below.
    val positionOf = HashMap<Uuid, Int>(order.size)
    for ((index, id) in order.withIndex()) positionOf[id] = index

    // Merged seal view: this walk's discoveries shadow the carried ones (identical in the
    // common case — the replay is deterministic over a fixed canonical order).
    fun sealOf(account: AccountId): Uuid? = walkSeals[account] ?: carriedSeals[account]

    fun isActive(account: AccountId): Boolean =
        members[account]?.status == RoomMemberStatus.ACTIVE

    // Plain target match (accounts-only — no device-cascade disjunction). A self-leave
    // revokes its author, so it seals the leaver's backdated admin-gated acts like any
    // `MemberRemove`.
    fun revokesPrincipal(ev: RoomEventPayload, target: AccountId): Boolean = when (ev) {
        is RoomEventPayload.MemberRemove -> ev.targetAccountId == target
        is RoomEventPayload.RemoveAdmin -> ev.targetAccountId == target
        else -> false
    }

    fun mutuallyRevoked(seal: Uuid, id: Uuid, event: RoomEventPayload, author: AccountId): Boolean {
        if (id in ancestors.getValue(seal) || seal in ancestors.getValue(id)) return false
        val sealNode = nodes[seal] ?: return false
        val sealEvent = sealNode.event ?: return false
        return revokesPrincipal(event, sealNode.authorAccountId) &&
                revokesPrincipal(sealEvent, author)
    }

    fun effectiveAdmin(account: AccountId, id: Uuid, event: RoomEventPayload): Boolean {
        val member = members[account] ?: return false
        if (member.status != RoomMemberStatus.ACTIVE) return false
        if (member.role == RoomMemberRole.ADMIN || member.role == RoomMemberRole.OWNER) return true
        // Demoted only by a concurrent mutual revoker → still admin here;
        // settled demotions count.
        val walkSeal = walkSeals[account]
        if (walkSeal != null && mutuallyRevoked(walkSeal, id, event, account)) return true
        val carriedSeal = carriedSeals[account]
        return carriedSeal != null && mutuallyRevoked(carriedSeal, id, event, account)
    }

    // Seal: voids admin-gated events at-or-before the seal that sit outside its ancestry.
    // Post-seal events are positional (re-grants reopen).
    fun sealed(account: AccountId, id: Uuid, event: RoomEventPayload): Boolean {
        val seal = sealOf(account) ?: return false
        if (seal == id) return false
        if (mutuallyRevoked(seal, id, event, account)) return false
        if (positionOf.getValue(id) > positionOf.getValue(seal)) return false
        return id !in ancestors.getValue(seal)
    }

    /** Mint the interval-scoped seal on [target]; first in canonical order defines it. */
    fun mintSeal(target: AccountId, id: Uuid) {
        if (target !in walkSeals) walkSeals[target] = id
    }

    for (id in order) {
        val node = nodes.getValue(id)
        val event = node.event ?: continue
        val author = node.authorAccountId
        when (event) {
            is RoomEventPayload.RoomCreated -> {
                if (id != genesis?.nodeId) continue
                // Genesis: the author is owner by definition; the member list seeds the room.
                members[author] = FoldRoomMember(author, RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE)
                for (memberId in event.initialMemberIds) {
                    if (memberId != author && memberId !in members) {
                        members[memberId] =
                            FoldRoomMember(memberId, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)
                    }
                }
                owner = author
            }

            is RoomEventPayload.MemberAdd -> {
                if (!effectiveAdmin(author, id, event) || sealed(author, id, event)) continue
                val current = members[event.targetAccountId]
                if (current == null || current.status == RoomMemberStatus.REMOVED) {
                    // Fresh add, or re-add: admin never resurrects on `MemberAdd`.
                    members[event.targetAccountId] = FoldRoomMember(
                        event.targetAccountId, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE,
                    )
                }
                // Duplicate add of an ACTIVE member: ignored.
            }

            is RoomEventPayload.MemberRemove -> {
                val target = event.targetAccountId
                if (author == target && author == owner) {
                    // Owner handover: the owner's own leave carrying a successor. Honored
                    // only in that shape — one atomic transition, no zero-owner window.
                    val successor = event.successorAccountId
                    val successorMember = successor?.let { members[it] }
                    if (successor == null || successor == author ||
                        successorMember == null || successorMember.status != RoomMemberStatus.ACTIVE
                    ) {
                        continue
                    }
                    members[author] =
                        FoldRoomMember(author, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, id)
                    mintSeal(author, id)
                    members[successor] = successorMember.copy(role = RoomMemberRole.OWNER, removalNodeId = null)
                    owner = successor
                } else {
                    // The successor field is owner-only: any other `MemberRemove` carrying
                    // one is ignored whole (no smuggling).
                    if (event.successorAccountId != null) continue
                    if (!isActive(target)) continue
                    if (author != target) {
                        // Admin-gated removal of another member; the owner is irrevocable.
                        if (target == owner) continue
                        if (!effectiveAdmin(author, id, event) || sealed(author, id, event)) continue
                    }
                    // Own-account leave (non-owner): always allowed. Removal resets the
                    // role — admin-ness is meaningless while removed, and the projection
                    // never shows OWNER on a removed row.
                    members[target] =
                        FoldRoomMember(target, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, id)
                    mintSeal(target, id)
                }
            }

            is RoomEventPayload.AddAdmin -> {
                if (!effectiveAdmin(author, id, event) || sealed(author, id, event)) continue
                val current = members[event.targetAccountId] ?: continue
                if (current.status != RoomMemberStatus.ACTIVE) continue
                if (current.role == RoomMemberRole.MEMBER) {
                    members[event.targetAccountId] = current.copy(role = RoomMemberRole.ADMIN)
                }
                // Granting an ADMIN/OWNER: no-op.
            }

            is RoomEventPayload.RemoveAdmin -> {
                if (!effectiveAdmin(author, id, event) || sealed(author, id, event)) continue
                val current = members[event.targetAccountId] ?: continue
                if (current.status != RoomMemberStatus.ACTIVE) continue
                // The owner is irrevocable: no forgery can strip the room's repair path.
                if (event.targetAccountId == owner) continue
                members[event.targetAccountId] = current.copy(role = RoomMemberRole.MEMBER)
                mintSeal(event.targetAccountId, id)
            }
        }
    }

    return RoomReplayResult(
        output = RoomFoldOutput(members = members, ownerAccountId = owner),
        seals = walkSeals,
    )
}
