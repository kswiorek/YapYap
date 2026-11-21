package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.candidate.AttemptExecutor
import org.yapyap.routing.candidate.AttemptState
import org.yapyap.routing.candidate.BuiltAttempt
import org.yapyap.routing.candidate.CandidateAttemptStore
import org.yapyap.routing.candidate.CandidateRequestCore
import org.yapyap.routing.candidate.CandidateRow
import org.yapyap.routing.candidate.CandidateStrategy
import org.yapyap.routing.candidate.CandidateWaiting
import org.yapyap.routing.candidate.SuccessAction
import org.yapyap.routing.file.fetch.FileFetchManager
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRequestAttemptStore
import org.yapyap.routing.store.FileRequestClass
import org.yapyap.routing.store.OutboxTag
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * File request loop (design §9): one candidate at a time.
 *
 * Small files: sealed REQUEST through the outbox, ACK, then WAITING for the
 * OFFER; DECLINED moves to the next candidate. The sealed REQUEST is
 * direct-only — it never goes through a relay. Runs on the shared candidate
 * core with an outbox executor.
 *
 * Large files: the same one-candidate-at-a-time loop on the shared core, but the
 * attempt opens a WebRTC session and runs a fetch (no WAITING state, no outbox).
 * FETCH_OK and FETCH_DECLINED are the attempt's outcomes. An unreachable candidate
 * is not a decline — the request stays PENDING until a candidate comes online.
 * Online-but-unconnectable is "no direct connection" (never over Tor).
 */
internal class FileRequestProcessor(
    ctx: RoutingContext,
    private val attempts: FileRequestAttemptStore,
    private val requests: FileRequestSource,
    outboxProcessor: OutboxProcessor,
    peerPolicy: SyncPeerPolicy,
    maxIdlePoll: StateFlow<Duration>,
    private val events: MutableSharedFlow<FileEvent>,
    /** No candidate has an open link: whoever keeps links open may want to try these now. */
    onNoLink: suspend (PeerId) -> Unit = {},
    sessionOpener: ProactiveSessionOpener,
    fetchManager: FileFetchManager,
) {
    private val strategy = FileRequestStrategy(ctx, attempts, requests, outboxProcessor, events)
    private val smallCore = CandidateRequestCore(
        ctx = ctx,
        strategy = strategy,
        peerPolicy = peerPolicy,
        executor = AttemptExecutor.Outbox(outboxProcessor),
        maxIdlePoll = maxIdlePoll,
        onNoLink = onNoLink,
    )
    private val fetchStrategy = FileFetchStrategy(ctx, attempts, requests, sessionOpener, fetchManager, events)
    private val fetchCore = CandidateRequestCore(
        ctx = ctx,
        strategy = fetchStrategy,
        peerPolicy = peerPolicy,
        executor = AttemptExecutor.Call(
            ctx.routerConfig.map { it.maxConcurrentFetches },
            fetchStrategy::runFetch,
        ),
        maxIdlePoll = maxIdlePoll,
        onNoLink = onNoLink,
    )

    fun runIn(scope: CoroutineScope): Job = scope.launch {
        launch { smallCore.runIn(this) }
        launch { fetchCore.runIn(this) }
    }

    fun wake() {
        smallCore.wake()
        fetchCore.wake()
    }

    /** The loop that owns [fileId]'s row, or null when it is not tracked. */
    private suspend fun coreFor(fileId: FileId): CandidateRequestCore<FileId>? =
        when (attempts.findById(fileId)?.requestClass) {
            FileRequestClass.SMALL -> smallCore
            FileRequestClass.LARGE -> fetchCore
            null -> null
        }

    /** Starts asking for [fileId] (idempotent). The class comes from the total bytes;
     * an unknown total takes the small path (its REQUEST is harmless: holders DECLINE
     * above the limit and the class pins on first start). */
    suspend fun start(fileId: FileId, firstAttemptAt: Instant) {
        val total = requests.requestTotalBytes(fileId)
        val requestClass =
            if (total != null && total > SMALL_FILE_MAX_BYTES) FileRequestClass.LARGE
            else FileRequestClass.SMALL
        attempts.start(fileId, requestClass, firstAttemptAt)
        wake()
    }

    /** How one attempt's outbox entry ended (small path only; large never touches the outbox). */
    suspend fun onAttemptOutcome(outcome: OutboxOutcome) = smallCore.onAttemptOutcome(outcome)

    /**
     * The holder's OFFER arrived: the serve transfer takes over. Drops our asking
     * state (a proactive push also lands here — with no attempt row it is a no-op)
     * and reports satisfaction to the orchestrator.
     */
    suspend fun onOfferReceived(fileId: FileId) {
        val core = coreFor(fileId) ?: return
        core.forgetExternal(fileId)
        events.emit(FileEvent.RequestSatisfied(fileId))
        core.wake()
    }

    suspend fun onDeclined(fileId: FileId, deviceId: PeerId) {
        coreFor(fileId)?.onDeclined(fileId, deviceId)
    }
}

/** Small-path plug into the shared candidate core: coverage-built REQUESTs, ACK→WAITING. */
internal class FileRequestStrategy(
    private val ctx: RoutingContext,
    private val store: FileRequestAttemptStore,
    private val requests: FileRequestSource,
    private val outbox: OutboxProcessor,
    private val events: MutableSharedFlow<FileEvent>,
) : CandidateStrategy<FileId> {
    private val dock = object : CandidateAttemptStore<FileId> {
        override suspend fun start(id: FileId, firstAttemptAt: Instant) =
            store.start(id, FileRequestClass.SMALL, firstAttemptAt)
        override suspend fun drop(id: FileId) = store.drop(id)
        override suspend fun earliestDueAt(): Instant? = store.earliestDueAt(FileRequestClass.SMALL)
        override suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<FileId>> =
            store.findDue(now, limit, FileRequestClass.SMALL).map { row ->
                val device = row.waitingDevice
                val until = row.waitingUntil
                val waiting = if (device != null && until != null) CandidateWaiting(device, until) else null
                CandidateRow(row.fileId, row.attempts, row.declinedDevices, waiting)
            }

        override suspend fun recordAttempt(id: FileId, nextAttemptAt: Instant) =
            store.recordAttempt(id, nextAttemptAt)

        override suspend fun postpone(id: FileId, nextAttemptAt: Instant) = store.postpone(id, nextAttemptAt)
        override suspend fun advanceToNow(ids: Collection<FileId>, now: Instant) = store.advanceToNow(ids, now)
        override suspend fun declinedDevices(id: FileId): Set<PeerId> = store.declinedDevices(id)
        override suspend fun addDeclined(id: FileId, device: PeerId) = store.addDeclined(id, device)
        override suspend fun removeDeclined(id: FileId, devices: Collection<PeerId>) =
            store.removeDeclined(id, devices)
        override suspend fun setWaiting(id: FileId, device: PeerId, until: Instant) {
            val row = store.findById(id) ?: return
            val transferId = row.requestTransferId ?: return
            store.setWaiting(id, device, until, transferId)
        }

        override suspend fun clearWaiting(id: FileId) = store.clearWaiting(id)
    }

    override val attempts: CandidateAttemptStore<FileId> = dock

    override suspend fun candidateAccounts(id: FileId): List<AccountId> = requests.requestCandidates(id)

    // Same device first after a block mismatch (design §8/§9).
    override suspend fun preferredDevice(id: FileId): PeerId? = requests.preferDevice(id)

    override suspend fun idsWithCandidate(account: AccountId): List<FileId> =
        store.idsOf(FileRequestClass.SMALL).filter { id -> account in requests.requestCandidates(id) }

    override suspend fun isWanted(id: FileId): Boolean = requests.heldCoverage(id) != null

    override suspend fun buildAttempt(id: FileId, device: PeerId, now: Instant): BuiltAttempt? {
        // Nothing held yet is normal for a fresh request: ask from zero. A null means
        // the file needs no request (already READY) — the core drops the row. Seal
        // failures propagate: the core logs and re-arms on backoff.
        val coverage = requests.heldCoverage(id) ?: return null
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

    override suspend fun attemptState(id: FileId): AttemptState {
        val row = store.findById(id) ?: return AttemptState.None
        val transferId = row.requestTransferId ?: return AttemptState.None
        val queued = outbox.hasQueued(OutboxTag(OutboxTag.Kind.FILE_REQUEST, transferId.value))
        return if (queued) AttemptState.Pending else AttemptState.None
    }

    override suspend fun resolveOutcome(outcome: OutboxOutcome): FileId? {
        if (outcome.tag.kind != OutboxTag.Kind.FILE_REQUEST) return null
        return store.findByTransferId(TransferId(outcome.tag.id))?.fileId
    }

    override suspend fun onSuccess(id: FileId, device: PeerId): SuccessAction {
        // ACK ("I hold it, serving"): wait for the OFFER with a timeout; the reply is on its way.
        return SuccessAction.AwaitUntil(ctx.clock.now() + ctx.routerConfig.value.fileRequestWaitTimeout)
    }

    override suspend fun onExhausted(id: FileId): Boolean {
        attempts.drop(id)
        try {
            events.emit(FileEvent.RequestUnavailable(id))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return true
    }
}
