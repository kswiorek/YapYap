package org.yapyap.routing.sync

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.router.RouterConfig

interface SyncPayloadProvider {
    suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload>
}

class DefaultSyncPayloadProvider(
    private val messageRepository: MessageRepository,
    private val routerConfig: StateFlow<RouterConfig>,
) : SyncPayloadProvider {

    override suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload> {
        val roomId = syncRequest.roomId
        // Page size is purely the responder's policy; the requester's retry loop
        // re-requests until every target arrives, so no per-request limit is needed.
        val limit = routerConfig.value.syncMaxMessages
        // The requester may send its whole frontier; bound how much of it we honor.
        // TODO(sync-limits): decide the policy for oversized knownIds (truncate vs refuse).
        val knownIds = syncRequest.knownIds.take(routerConfig.value.syncMaxKnownIds).toSet()

        // Walk down from the requested targets over parent edges, stopping at the
        // requester's known frontier (everything below a known tip is present there).
        // Gaps of our own simply yield nothing — the requester re-chases the
        // still-missing IDs against other candidates.
        val collected = LinkedHashMap<kotlin.uuid.Uuid, MessagePayload>()
        val visited = HashSet<kotlin.uuid.Uuid>()
        val queue = ArrayDeque<kotlin.uuid.Uuid>()
        for (id in syncRequest.missingIds) {
            if (visited.add(id)) queue.add(id)
        }
        while (queue.isNotEmpty() && collected.size < limit) {
            val id = queue.removeFirst()
            if (id in knownIds) continue
            val row = messageRepository.findById(id) ?: continue
            if (row.payload.roomId != roomId) continue
            collected[id] = row.payload
            for (parentId in row.payload.prevIds) {
                if (visited.add(parentId)) queue.add(parentId)
            }
        }

        // Topological order (lamport-ascending is valid: child > every parent), so
        // parents land before children and the requester mints no transient orphans.
        return collected.values
            .sortedWith(compareBy({ it.lamportClock }, { it.createdAt }, { it.messageId }))
            .take(limit)
    }
}
