package org.yapyap.orchestrator.sync

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.store.PendingSyncRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.routing.message.port.SyncPayloadProvider
import kotlin.uuid.Uuid

internal class DefaultSyncPayloadProvider(
    private val messageRepository: MessageRepository,
    private val pendingSyncRepository: PendingSyncRepository,
    private val orchestratorConfig: StateFlow<OrchestratorConfig>,
    private val visibility: RoomVisibility,
) : SyncPayloadProvider {

    override suspend fun syncRequest(syncId: Uuid): SyncPayload.Request? =
        pendingSyncRepository.buildSyncRequest(syncId)

    override suspend fun syncCandidates(syncId: Uuid): List<AccountId> =
        pendingSyncRepository.candidateAccounts(syncId)

    override suspend fun syncsWithCandidate(account: AccountId): List<Uuid> =
        pendingSyncRepository.syncIdsWithCandidate(account)

    override suspend fun serveSync(request: SyncPayload.Request, requester: AccountId): List<DagPayload> {
        val roomId = request.roomId
        // Membership gate (docs/room events.md §5, narrowed to the removal boundary), decided
        // once per request by the shared RoomVisibility rule: ACTIVE members are served the
        // room; REMOVED members only their member-era history (the ancestor closure of their
        // removal node, removal event included); never-members get the generic NACK.
        // Denial returns empty so the router declines the request (a generic NACK), without
        // revealing whether the room is empty or access was denied.
        val removalRoot: Uuid? = when (val scope = visibility.scope(roomId, requester)) {
            VisibilityScope.All -> null
            is VisibilityScope.UpTo -> {
                AppLog.info(
                    component = LogComponent.ORCHESTRATOR,
                    event = LogEvent.SYNC_BOUNDED_SERVE,
                    message = "Serving removed member their member-era history only",
                    fields = mapOf("requester" to requester, "roomId" to roomId),
                )
                scope.removalNode
            }

            VisibilityScope.None -> {
                AppLog.info(
                    component = LogComponent.ORCHESTRATOR,
                    event = LogEvent.SYNC_NO_MESSAGES_FOUND,
                    message = "No messages to sync for peer",
                    fields = mapOf("requester" to requester, "roomId" to roomId),
                )
                return emptyList()
            }
        }
        // Page size is purely the responder's policy; the requester's retry loop
        // re-requests until every target arrives, so no per-request limit is needed.
        val limit = orchestratorConfig.value.syncMaxMessages
        // The requester may send its whole frontier; bound how much of it we
        // honor. Policy: truncate, never refuse (docs/room events.md §6).
        // Truncation is merely wasteful, never incorrect: the target is always
        // collected first (BFS root), partial batches converge via the
        // hold-minted rows (each delivered message reveals its own gaps), and
        // re-sent known messages dedup on ingest. Refusal would force the
        // requester to guess a subset it cannot know (the target's ancestry is
        // exactly the unknown) plus an extra round trip — and a refusal NACK
        // would wrongly mark a peer attempted that may hold the messages.
        val knownIds = request.knownIds.take(orchestratorConfig.value.syncMaxKnownIds).toSet()

        // Walk down from the requested targets over parent edges, stopping at the
        // requester's known frontier (everything below a known tip is present there).
        // Gaps of our own simply yield nothing — the requester re-chases the
        // still-missing IDs against other candidates.
        // REMOVED branch: the walk is rooted at the removal node, not at the requester's
        // missing ids. The removal event itself always serves (a fresh removed device needs
        // it to flip its own row), and everything collected is member-era history by
        // construction: the walk only goes to parents, so it can never leave the closure.
        val collected = LinkedHashMap<Uuid, DagPayload>()
        val visited = HashSet<Uuid>()
        val queue = ArrayDeque<Uuid>()
        val seeds = removalRoot?.let { listOf(it) } ?: request.missingIds
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
        val ordered = ArrayList<DagPayload>(collected.size)
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
}
