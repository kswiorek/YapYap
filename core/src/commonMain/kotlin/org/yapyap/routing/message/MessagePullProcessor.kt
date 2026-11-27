package org.yapyap.routing.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.routing.candidate.AttemptExecutor
import org.yapyap.routing.candidate.AttemptState
import org.yapyap.routing.candidate.BuiltAttempt
import org.yapyap.routing.candidate.CandidateAttemptStore
import org.yapyap.routing.candidate.CandidateRequestCore
import org.yapyap.routing.candidate.CandidateStrategy
import org.yapyap.routing.candidate.OutboxAttemptStrategy
import org.yapyap.routing.candidate.SuccessAction
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.message.port.SyncPayloadProvider
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.SyncAttemptStore
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Asks candidate devices for each pending sync — the pull path of messages, one device per
 * attempt. What to ask for and which accounts may hold it come from [SyncPayloadProvider]; this
 * decides which device and when.
 *
 * Only devices with an open link are asked; with none, the candidates' links are nudged and
 * the sync waits until one comes up ([PeerLinks.linkUps][org.yapyap.routing.link.PeerLinks.linkUps]
 * makes it due at once).
 *
 * Each attempt is one sync request handed to the outbox (tag [OutboxTag.Kind.SYNC]) with a
 * short lifetime, and its outcome comes back through [onAttemptOutcome]:
 * - a decline ([PacketNackReason.DECLINED][org.yapyap.protocol.envelopes.PacketNackReason.DECLINED],
 *   or `UNSUPPORTED_TYPE` from a build that can't serve) rules that device out for this sync;
 * - expiry or any other NACK leaves the device askable but behind other candidates;
 * either way the next candidate is asked at once if there is one. An ACK leaves the backoff
 * to schedule the next attempt, since the answer is already on its way.
 */
internal class MessagePullProcessor(
    private val ctx: RoutingContext,
    attempts: SyncAttemptStore,
    syncs: SyncPayloadProvider,
    envelopes: OutboundMessenger,
    outboxProcessor: OutboxProcessor,
    peerPolicy: SyncPeerPolicy,
    maxIdlePoll: StateFlow<Duration>,
    linkKeeper: LinkKeeper,
) {
    private val strategy = MessagePullStrategy(ctx, attempts, syncs, envelopes, outboxProcessor)
    private val core = CandidateRequestCore(
        ctx = ctx,
        strategy = strategy,
        peerPolicy = peerPolicy,
        executor = AttemptExecutor.Outbox(outboxProcessor, strategy),
        maxIdlePoll = maxIdlePoll,
        linkKeeper = linkKeeper,
    )

    fun runIn(scope: CoroutineScope): Job = core.runIn(scope)

    fun wake() = core.wake()

    /** Starts asking for [syncId] (idempotent). */
    suspend fun start(syncId: Uuid, firstAttemptAt: Instant) = core.start(syncId, firstAttemptAt)

    /**
     * [account] asserts it can serve [syncId] after all. When every candidate device has
     * declined (the sync is inert), its devices may be asked again; mid-round rotation
     * is left alone, so one candidate cannot hog the requests while others never get a turn.
     */
    suspend fun reopen(syncId: Uuid, account: AccountId) = core.reopen(syncId, account)

    /** How one attempt's outbox entry ended. */
    suspend fun onAttemptOutcome(outcome: OutboxOutcome) = core.onAttemptOutcome(outcome)
}

/** The pull path's plug into the shared candidate core: the store's view, request bytes, ACK-is-quiet. */
private class MessagePullStrategy(
    private val ctx: RoutingContext,
    store: SyncAttemptStore,
    private val syncs: SyncPayloadProvider,
    private val envelopes: OutboundMessenger,
    private val outboxProcessor: OutboxProcessor,
) : CandidateStrategy<Uuid>, OutboxAttemptStrategy<Uuid> {
    override val attempts: CandidateAttemptStore<Uuid> = store.candidateStore()

    override suspend fun candidateAccounts(id: Uuid): List<AccountId> = syncs.syncCandidates(id)

    override suspend fun idsWithCandidate(account: AccountId): List<Uuid> = syncs.syncsWithCandidate(account)

    override suspend fun isWanted(id: Uuid): Boolean = syncs.syncRequest(id) != null

    override suspend fun buildAttempt(id: Uuid, device: PeerId, now: Instant): BuiltAttempt? {
        val request: SyncPayload.Request = syncs.syncRequest(id) ?: return null
        val envelope = envelopes.buildEnvelope(device, request, ctx.routerConfig.value.syncAttemptTimeout)
        return BuiltAttempt(envelope, OutboxTag(OutboxTag.Kind.SYNC, id))
    }

    override suspend fun attemptState(id: Uuid): AttemptState =
        if (outboxProcessor.hasQueued(OutboxTag(OutboxTag.Kind.SYNC, id))) AttemptState.Pending else AttemptState.None

    override suspend fun resolveOutcome(outcome: OutboxOutcome): Uuid? =
        outcome.tag.id.takeIf { outcome.tag.kind == OutboxTag.Kind.SYNC }

    override suspend fun onSuccess(id: Uuid, device: PeerId): SuccessAction = SuccessAction.Done
}
