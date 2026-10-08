package org.yapyap.routing.sync

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.router.RouterConfig
import kotlin.uuid.Uuid

interface SyncPayloadProvider {
    suspend fun getMessages(syncRequest: SyncRequest, peerId: PeerId): List<MessagePayload>

    /**
     * Removal-contradiction lookup (docs/room events.md §6): the removal node
     * for ([roomId], [accountId]) when the fold says the account was removed
     * from the room and the node is still held; null otherwise (no row, not
     * removed, no node id, node evicted). Callers cannot distinguish the null
     * reasons — all mean "nothing to re-push".
     */
    suspend fun removalNodeFor(roomId: RoomId, accountId: AccountId): MessagePayload?
}

class DefaultSyncPayloadProvider(
    private val messageRepository: MessageRepository,
    private val routerConfig: StateFlow<RouterConfig>,
    private val roomRepository: RoomRepository,
    private val identityResolver: IdentityResolver,
) : SyncPayloadProvider {

    override suspend fun getMessages(syncRequest: SyncRequest, peerId: PeerId): List<MessagePayload> {
        val roomId = syncRequest.roomId
        // Membership gate (docs/room events.md §5, narrowed to the removal boundary):
        // ACTIVE members are served the room as today; REMOVED members are served
        // only their member-era history (the ancestor closure of their defining
        // removal node, removal event included — everything they legitimately
        // held, nothing after); never-members get the generic NACK.
        // Denial returns empty so the caller emits the generic "no messages" NACK,
        // without revealing whether the room is empty or access was denied.
        // A REMOVED requester is served by walking from the removal node itself:
        // the walk can never leave the member-era closure, so no bound set is
        // needed — the boundary is structural, not a filter.
        val removalRoot: Uuid? = if (roomId in roomRepository.roomsOfPeer(peerId)) {
            null
        } else {
            val requester = identityResolver.getAccountIdForDevice(peerId)
            val row = requester?.let { roomRepository.memberRowOf(roomId, it) }
            if (row?.status != RoomMemberStatus.REMOVED || row.removalNodeId == null) {
                AppLog.info(
                    component = LogComponent.ROUTER,
                    event = LogEvent.SYNC_NO_MESSAGES_FOUND,
                    message = "No messages to sync for peer",
                    fields = mapOf("peerId" to peerId, "roomId" to roomId),
                )
                return emptyList()
            }
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_BOUNDED_SERVE,
                message = "Serving removed member their member-era history only",
                fields = mapOf("peerId" to peerId, "roomId" to roomId),
            )
            row.removalNodeId
        }
        // Page size is purely the responder's policy; the requester's retry loop
        // re-requests until every target arrives, so no per-request limit is needed.
        val limit = routerConfig.value.syncMaxMessages
        // The requester may send its whole frontier; bound how much of it we
        // honor. Policy: truncate, never refuse (docs/room events.md §6).
        // Truncation is merely wasteful, never incorrect: the target is always
        // collected first (BFS root), partial batches converge via the
        // hold-minted rows (each delivered message reveals its own gaps), and
        // re-sent known messages dedup on ingest. Refusal would force the
        // requester to guess a subset it cannot know (the target's ancestry is
        // exactly the unknown) plus an extra round trip — and a refusal NACK
        // would wrongly mark a peer attempted that may hold the messages.
        val knownIds = syncRequest.knownIds.take(routerConfig.value.syncMaxKnownIds).toSet()

        // Walk down from the requested targets over parent edges, stopping at the
        // requester's known frontier (everything below a known tip is present there).
        // Gaps of our own simply yield nothing — the requester re-chases the
        // still-missing IDs against other candidates.
        // REMOVED branch: the walk is rooted at the removal node, not at the
        // requester's missing ids — the removal event itself always serves (a fresh
        // removed device needs it to flip its own row), and everything collected
        // is member-era history by construction.
        val collected = LinkedHashMap<Uuid, MessagePayload>()
        val visited = HashSet<Uuid>()
        val queue = ArrayDeque<Uuid>()
        val seeds = removalRoot?.let { listOf(it) } ?: syncRequest.missingIds
        for (id in seeds) {
            if (visited.add(id)) queue.add(id)
        }
        while (queue.isNotEmpty() && collected.size < limit) {
            val id = queue.removeFirst()
            if (id in knownIds) continue
            val row = messageRepository.findById(id) ?: continue
            if (row.payload.roomId != roomId) continue
            for (parentId in row.payload.prevIds) {
                if (visited.add(parentId)) queue.add(parentId)
            }
            collected[id] = row.payload
        }

        // Topological order over the parent edges present in the collected set (Kahn's
        // algorithm), so parents land before children and the requester mints no transient
        // orphans. Edges to messages outside the set (our own gaps, the requester's knownIds)
        // impose no constraint — those endpoints are either absent or already safe.
        // Nodes that never become ready (a forged causality cycle) cannot be ordered; they
        // are appended deterministically so nothing the responder holds is silently dropped —
        // the requester's retry loop still terminates against other candidates.
        val pendingParents = HashMap<Uuid, MutableSet<Uuid>>()
        val childrenOf = HashMap<Uuid, MutableList<Uuid>>()
        for ((id, payload) in collected) {
            for (parentId in payload.prevIds) {
                if (parentId in collected) {
                    pendingParents.getOrPut(id) { mutableSetOf() }.add(parentId)
                    childrenOf.getOrPut(parentId) { mutableListOf() }.add(id)
                }
            }
        }
        val ready = ArrayDeque<Uuid>()
        for (id in collected.keys.sortedWith(compareBy({ collected.getValue(it).createdAt }, { it }))) {
            if (id !in pendingParents) ready.add(id)
        }
        val ordered = ArrayList<MessagePayload>(collected.size)
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            ordered += collected.getValue(id)
            for (child in childrenOf[id] ?: emptyList()) {
                if (pendingParents.getValue(child).remove(id) && pendingParents.getValue(child).isEmpty()) {
                    pendingParents.remove(child)
                    ready.add(child)
                }
            }
        }
        if (ordered.size < collected.size) {
            val emitted = ordered.mapTo(HashSet()) { it.messageId }
            collected.values
                .filter { it.messageId !in emitted }
                .sortedWith(compareBy({ it.createdAt }, { it.messageId }))
                .forEach { ordered += it }
        }
        return ordered
    }

    override suspend fun removalNodeFor(roomId: RoomId, accountId: AccountId): MessagePayload? {
        // Same removal boundary as the serve gate above: only a REMOVED row
        // with a defining node qualifies (the fold never sets a removal node
        // id on ACTIVE rows, but the explicit status check matches the gate).
        val row = roomRepository.memberRowOf(roomId, accountId) ?: return null
        if (row.status != RoomMemberStatus.REMOVED) return null
        val nodeId = row.removalNodeId ?: return null
        return messageRepository.findById(nodeId)?.payload
    }
}
