package org.yapyap.routing.sync

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import kotlin.uuid.Uuid

/**
 * Sync content, supplied from above the router: what is missing, which accounts may hold it,
 * and what may be served to whom are room and history knowledge. The router owns the asking —
 * which candidate device, when, and carrying requests and replies — keyed by the sync id.
 */
internal interface SyncPayloadProvider {
    /**
     * The request to send for [syncId] now (its target plus our current frontier as the
     * responder's stop set). Null once the sync is satisfied or gone: the router stops asking.
     */
    suspend fun syncRequest(syncId: Uuid): SyncRequest?

    /** Accounts that may hold [syncId]'s target. */
    suspend fun syncCandidates(syncId: Uuid): List<AccountId>

    /** Pending syncs [account] is a candidate for. */
    suspend fun syncsWithCandidate(account: AccountId): List<Uuid>

    /** What to send [requester] for [request]; empty when nothing is served (the request is declined). */
    suspend fun syncMessages(request: SyncRequest, requester: AccountId): List<MessagePayload>
}
