package org.yapyap.routing.message.port

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.SyncPayload
import kotlin.uuid.Uuid

/**
 * Message pull content, supplied from above the router: what is missing, which accounts may hold
 * it, and what may be served to whom are room and history knowledge. The router owns the asking —
 * which candidate device, when, and carrying requests and replies — keyed by the sync id.
 *
 * The ask side ([syncRequest], [syncCandidates], [syncsWithCandidate]) is a pull the retry loop
 * makes at the moment it needs it. The serve side ([serveSync]) is the receive-side direction:
 * it is called synchronously during inbound handling, and its answer *is* the wire disposition
 * — content → ACK plus the reply, empty → DECLINED (design §8: a sync request is processed when
 * its reply is served).
 */
internal interface SyncPayloadProvider {
    /**
     * The request to send for [syncId] now (its target plus our current frontier as the
     * responder's stop set). Null once the sync is satisfied or gone: the router stops asking.
     */
    suspend fun syncRequest(syncId: Uuid): SyncPayload.Request?

    /** Accounts that may hold [syncId]'s target. */
    suspend fun syncCandidates(syncId: Uuid): List<AccountId>

    /** Pending syncs [account] is a candidate for. */
    suspend fun syncsWithCandidate(account: AccountId): List<Uuid>

    /**
     * What to serve [requester] for [request]; empty when nothing is served (the request is
     * declined). Called synchronously from inbound handling, before the packet's disposition.
     */
    suspend fun serveSync(request: SyncPayload.Request, requester: AccountId): List<DagPayload>
}
