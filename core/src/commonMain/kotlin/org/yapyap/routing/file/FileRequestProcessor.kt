package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.*
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRequestAttemptStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.sync.*
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Small-path file request loop (design §9): one candidate at a time, ACK, then WAITING
 * for the OFFER, DECLINED moves to the next candidate. The sealed REQUEST is
 * direct-only — it never goes through a relay.
 *
 * Runs on the shared candidate core ([CandidateRequestCore], step 15); the sync-shaped
 * loop (due selection, link-filtered picking, unanswered/declined bookkeeping, backoff)
 * is reused, only the request bytes and the ACK→WAITING handoff differ.
 */
internal class FileRequestProcessor(
    ctx: RoutingContext,
    private val attempts: FileRequestAttemptStore,
    gate: FileIngestPort,
    outboxProcessor: OutboxProcessor,
    peerPolicy: SyncPeerPolicy,
    maxIdlePoll: StateFlow<Duration>,
    private val events: MutableSharedFlow<FileEvent>,
    /** No candidate has an open link: whoever keeps links open may want to try these now. */
    onNoLink: suspend (PeerId) -> Unit = {},
) {
    private val strategy = FileRequestStrategy(ctx, attempts, gate, outboxProcessor, events)
    private val core = CandidateRequestCore(
        ctx = ctx,
        strategy = strategy,
        peerPolicy = peerPolicy,
        outboxProcessor = outboxProcessor,
        maxIdlePoll = maxIdlePoll,
        onNoLink = onNoLink,
    )

    init {
        strategy.core = core
    }

    fun runIn(scope: CoroutineScope): Job = core.runIn(scope)

    fun wake() = core.wake()

    /** Starts asking for [fileId] (idempotent). */
    suspend fun start(fileId: FileId, firstAttemptAt: Instant) = core.start(fileId, firstAttemptAt)

    /** How one attempt's outbox entry ended. */
    suspend fun onAttemptOutcome(outcome: OutboxOutcome) = core.onAttemptOutcome(outcome)

    /**
     * The holder's OFFER arrived: the serve transfer takes over. Drops our asking
     * state (a proactive push also lands here — with no attempt row it is a no-op)
     * and reports satisfaction to the orchestrator.
     */
    suspend fun onOfferReceived(fileId: FileId) {
        if (attempts.findById(fileId) == null) return
        attempts.drop(fileId)
        events.emit(FileEvent.RequestSatisfied(fileId))
        wake()
    }

    suspend fun onDeclined(fileId: FileId, deviceId: PeerId) = core.onDeclined(fileId, deviceId)
}

/** File-request plug into the shared candidate core: coverage-built REQUESTs, ACK→WAITING. */
internal class FileRequestStrategy(
    private val ctx: RoutingContext,
    private val store: FileRequestAttemptStore,
    private val gate: FileIngestPort,
    private val outbox: OutboxProcessor,
    private val events: MutableSharedFlow<FileEvent>,
) : CandidateStrategy<FileId> {
    /**
     * Back-reference for WAITING-timeout deprioritisation (see `isAttemptPending`);
     * wired by the processor after construction (same late-binding pattern as
     * `DelegatingFileIngestPort`).
     */
    internal lateinit var core: CandidateRequestCore<FileId>

    private val dock = object : CandidateAttemptStore<FileId> {
        override suspend fun start(id: FileId, firstAttemptAt: Instant) = store.start(id, firstAttemptAt)
        override suspend fun drop(id: FileId) = store.drop(id)
        override suspend fun earliestDueAt(): Instant? = store.earliestDueAt()
        override suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<FileId>> =
            store.findDue(now, limit).map { CandidateRow(it.fileId, it.attempts, it.declinedDevices) }

        override suspend fun recordAttempt(id: FileId, nextAttemptAt: Instant) =
            store.recordAttempt(id, nextAttemptAt)

        override suspend fun postpone(id: FileId, nextAttemptAt: Instant) = store.postpone(id, nextAttemptAt)
        override suspend fun advanceToNow(ids: Collection<FileId>, now: Instant) = store.advanceToNow(ids, now)
        override suspend fun declinedDevices(id: FileId): Set<PeerId> = store.declinedDevices(id)
        override suspend fun addDeclined(id: FileId, device: PeerId) = store.addDeclined(id, device)
        override suspend fun removeDeclined(id: FileId, devices: Collection<PeerId>) =
            store.removeDeclined(id, devices)
    }

    override val attempts: CandidateAttemptStore<FileId> = dock

    override suspend fun candidateAccounts(id: FileId): List<AccountId> = gate.requestCandidates(id)

    override suspend fun idsWithCandidate(account: AccountId): List<FileId> {
        // Which tracked files list this account as a candidate. Few live requests;
        // correctness over an index.
        return store.allIds().filter { id -> account in gate.requestCandidates(id) }
    }

    override suspend fun buildAttempt(id: FileId, device: PeerId, now: Instant): BuiltAttempt? {
        // Nothing held yet is normal for a fresh request: ask from zero. A null means
        // the file needs no request (already READY) — the core drops the row. Seal
        // failures propagate: the core logs and re-arms on backoff.
        val coverage = gate.heldCoverage(id) ?: return null
        val transferId = TransferId(Uuid.random())
        val payload = FilePayload.Request(
            fileId = id,
            contiguousBytes = coverage.contiguousBytes,
            missingRanges = coverage.missingRanges.take(FilePayload.Request.MAX_MISSING_RANGES),
        )
        val envelope = ctx.envelopeProtectionService.protectFile(
            payload,
            EnvelopeProtectContext(
                createdAt = now,
                sourceDeviceId = ctx.localDeviceId,
                targetDeviceId = device,
                securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
                transferId = transferId,
            ),
        )
        return BuiltAttempt(
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.FILE,
                dispositionRequested = true,
                createdAt = now,
                expiresAt = now + ctx.routerConfig.value.fileRequestAttemptTimeout,
                source = ctx.localDeviceId,
                target = device,
                payload = envelope.encode(),
            ),
            OutboxTag(OutboxTag.Kind.FILE_REQUEST, transferId.value),
        )
    }

    override suspend fun afterEnqueue(id: FileId, built: BuiltAttempt) {
        store.setRequestTransfer(id, TransferId(built.tag.id))
    }

    override suspend fun isAttemptPending(id: FileId): Boolean {
        val row = store.findById(id) ?: return false
        val until = row.waitingUntil
        val device = row.waitingDevice
        if (until != null && device != null) {
            if (ctx.clock.now() < until) return true
            // WAITING expired with no OFFER: deprioritize the holder behind the other
            // candidates (transient, never a refusal — // TODO: prefer this device again
            // after a hash mismatch, design §8) and ask the next one at once.
            store.clearWaiting(id)
            if (::core.isInitialized) core.noteSilent(id, device)
            return false
        }
        val transferId = row.requestTransferId ?: return false
        return outbox.hasQueued(OutboxTag(OutboxTag.Kind.FILE_REQUEST, transferId.value))
    }

    override suspend fun resolveOutcome(outcome: OutboxOutcome): FileId? {
        if (outcome.tag.kind != OutboxTag.Kind.FILE_REQUEST) return null
        return store.findByTransferId(TransferId(outcome.tag.id))?.fileId
    }

    override suspend fun onSuccess(id: FileId, device: PeerId) {
        // ACK ("I hold it, serving"): wait for the OFFER with a timeout; the reply is on its way.
        val row = store.findById(id) ?: return
        val transferId = row.requestTransferId ?: return
        store.setWaiting(
            id,
            device,
            ctx.clock.now() + ctx.routerConfig.value.fileRequestWaitTimeout,
            transferId,
        )
    }

    override suspend fun onExhausted(id: FileId): Boolean {
        events.emit(FileEvent.RequestUnavailable(id))
        return true
    }
}
