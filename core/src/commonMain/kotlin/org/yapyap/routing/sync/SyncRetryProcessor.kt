package org.yapyap.routing.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.SyncAttemptStore
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Asks candidate devices for each pending sync, one device per attempt. What to ask for and
 * which accounts may hold it come from [syncs]; this decides which device and when.
 *
 * Only devices with an open link are asked; with none, the candidates' links are nudged and
 * the sync waits until one comes up ([PeerLinks.linkUps][org.yapyap.routing.link.PeerLinks.linkUps]
 * makes it due at once).
 *
 * Each attempt is one sync request handed to the outbox (tag [OutboxTag.Kind.SYNC]) with a
 * short lifetime (`syncAttemptTimeout`), and its outcome comes back through [onAttemptOutcome]:
 * - a decline ([PacketNackReason.DECLINED][org.yapyap.protocol.envelopes.PacketNackReason.DECLINED],
 *   or `UNSUPPORTED_TYPE` from a build that can't serve) rules that device out for this sync;
 * - expiry or any other NACK leaves the device askable but behind other candidates;
 * either way the next candidate is asked at once if there is one. An ACK leaves the backoff
 * to schedule the next attempt, since the answer is already on its way.
 */
internal class SyncRetryProcessor(
    private val ctx: RoutingContext,
    attempts: SyncAttemptStore,
    syncs: SyncPayloadProvider,
    systemSender: SystemSender,
    outboxProcessor: OutboxProcessor,
    peerPolicy: SyncPeerPolicy,
    maxIdlePoll: StateFlow<Duration>,
    /** No candidate has an open link: whoever keeps links open may want to try these now. */
    onNoLink: suspend (PeerId) -> Unit = {},
) {
    private val core = CandidateRequestCore(
        ctx = ctx,
        strategy = SyncCandidateStrategy(ctx, attempts, syncs, systemSender, outboxProcessor),
        peerPolicy = peerPolicy,
        outboxProcessor = outboxProcessor,
        maxIdlePoll = maxIdlePoll,
        onNoLink = onNoLink,
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

    /** [deviceId] won't serve [syncId]: never ask it again for this sync; ask the next one now. */
    suspend fun onDeclined(syncId: Uuid, deviceId: PeerId) = core.onDeclined(syncId, deviceId)
}

/** Sync's plug into the shared candidate core: store adapter, request bytes, ACK-is-quiet. */
private class SyncCandidateStrategy(
    private val ctx: RoutingContext,
    store: SyncAttemptStore,
    private val syncs: SyncPayloadProvider,
    private val systemSender: SystemSender,
    private val outboxProcessor: OutboxProcessor,
) : CandidateStrategy<Uuid> {
    override val attempts: CandidateAttemptStore<Uuid> = SyncAttemptDock(store)

    override suspend fun candidateAccounts(id: Uuid): List<AccountId> = syncs.syncCandidates(id)

    override suspend fun idsWithCandidate(account: AccountId): List<Uuid> = syncs.syncsWithCandidate(account)

    override suspend fun buildAttempt(id: Uuid, device: PeerId, now: Instant): BuiltAttempt? {
        val request: SyncRequest = syncs.syncRequest(id) ?: return null
        val envelope = systemSender.buildForOutbox(device, request, ctx.routerConfig.value.syncAttemptTimeout)
        return BuiltAttempt(envelope, OutboxTag(OutboxTag.Kind.SYNC, id))
    }

    override suspend fun afterEnqueue(id: Uuid, built: BuiltAttempt) = Unit

    override suspend fun isAttemptPending(id: Uuid): Boolean =
        outboxProcessor.hasQueued(OutboxTag(OutboxTag.Kind.SYNC, id))

    override suspend fun resolveOutcome(outcome: OutboxOutcome): Uuid? =
        outcome.tag.id.takeIf { outcome.tag.kind == OutboxTag.Kind.SYNC }

    override suspend fun onSuccess(id: Uuid, device: PeerId) = Unit
}

/** Adapts the sync-shaped attempt store to the shared core without touching its shape. */
private class SyncAttemptDock(
    private val store: SyncAttemptStore,
) : CandidateAttemptStore<Uuid> {
    override suspend fun start(id: Uuid, firstAttemptAt: Instant) = store.start(id, firstAttemptAt)
    override suspend fun drop(id: Uuid) = store.drop(id)
    override suspend fun earliestDueAt(): Instant? = store.earliestDueAt()
    override suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<Uuid>> =
        store.findDue(now, limit).map { CandidateRow(it.syncId, it.attempts, it.declinedDevices) }

    override suspend fun recordAttempt(id: Uuid, nextAttemptAt: Instant) =
        store.recordAttempt(id, nextAttemptAt)

    override suspend fun postpone(id: Uuid, nextAttemptAt: Instant) = store.postpone(id, nextAttemptAt)
    override suspend fun advanceToNow(ids: Collection<Uuid>, now: Instant) = store.advanceToNow(ids, now)
    override suspend fun declinedDevices(id: Uuid): Set<PeerId> = store.declinedDevices(id)
    override suspend fun addDeclined(id: Uuid, device: PeerId) = store.addDeclined(id, device)
    override suspend fun removeDeclined(id: Uuid, devices: Collection<PeerId>) =
        store.removeDeclined(id, devices)
}
