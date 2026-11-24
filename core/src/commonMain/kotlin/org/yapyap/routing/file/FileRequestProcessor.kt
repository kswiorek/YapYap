package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.routing.candidate.AttemptExecutor
import org.yapyap.routing.candidate.AttemptState
import org.yapyap.routing.candidate.BuiltAttempt
import org.yapyap.routing.candidate.CandidateAttemptStore
import org.yapyap.routing.candidate.CandidateRequestCore
import org.yapyap.routing.candidate.CandidateRow
import org.yapyap.routing.candidate.CandidateStrategy
import org.yapyap.routing.candidate.CandidateWaiting
import org.yapyap.routing.candidate.SuccessAction
import org.yapyap.routing.file.fetch.FileFetchService
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.outbound.OutboxProcessor
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
 * serve's OFFER; DECLINED moves to the next candidate. The sealed REQUEST is
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
    /** No candidate has an open link: whoever keeps links open may want to try these now (Tor, small path). */
    linkKeeper: LinkKeeper,
    /** The WebRTC keeper: a large fetch needs that link, so its core nudges it and awaits it. */
    webRtcLinks: LinkKeeper,
    fetchService: FileFetchService,
) {
    private val strategy = FileRequestStrategy(ctx, attempts, requests, outboxProcessor, events)
    private val smallCore = CandidateRequestCore(
        ctx = ctx,
        strategy = strategy,
        peerPolicy = peerPolicy,
        executor = AttemptExecutor.Outbox(outboxProcessor),
        maxIdlePoll = maxIdlePoll,
        linkKeeper = linkKeeper,
    )
    private val fetchStrategy = FileFetchStrategy(ctx, attempts, requests, webRtcLinks, fetchService, events)
    private val fetchCore = CandidateRequestCore(
        ctx = ctx,
        strategy = fetchStrategy,
        peerPolicy = peerPolicy,
        executor = AttemptExecutor.Call(
            ctx.routerConfig.map { it.maxConcurrentFetches },
            fetchStrategy::runFetch,
        ),
        maxIdlePoll = maxIdlePoll,
        linkKeeper = webRtcLinks,
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
     * A seal of [fileId] arrived: its transfer takes over. Drops our asking state (a
     * proactive push also lands here — with no attempt row it is a no-op) and reports
     * satisfaction to the orchestrator.
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
            val attemptId = row.attemptId ?: return
            store.setWaiting(id, device, until, attemptId)
        }

        override suspend fun clearWaiting(id: FileId) = store.clearWaiting(id)
    }

    override val attempts: CandidateAttemptStore<FileId> = dock

    override suspend fun candidateAccounts(id: FileId): List<AccountId> = requests.requestCandidates(id)


    override suspend fun idsWithCandidate(account: AccountId): List<FileId> =
        store.idsOf(FileRequestClass.SMALL).filter { id -> account in requests.requestCandidates(id) }

    override suspend fun isWanted(id: FileId): Boolean = requests.heldCoverage(id) != null

    override suspend fun buildAttempt(id: FileId, device: PeerId, now: Instant): BuiltAttempt? {
        // Nothing held yet is normal for a fresh request: ask from zero. A null means
        // the file needs no request (already READY) — the core drops the row. Seal
        // failures propagate: the core logs and re-arms on backoff.
        val coverage = requests.heldCoverage(id) ?: return null
        val request = FileRequest(
            fileId = id,
            contiguousBytes = coverage.contiguousBytes,
            missingRanges = coverage.missingRanges.take(FileRequest.MAX_MISSING_RANGES),
        )
        val envelope = ctx.envelope(
            packetType = PacketType.FILE,
            target = device,
            routingHeader = FileHeader.Request.encode(),
            content = ctx.envelopeProtections.file.sealRequest(request, device),
            expiresAt = now + ctx.routerConfig.value.fileRequestAttemptTimeout,
            dispositionRequested = true,
        )
        // The attempt's id correlates its outbox outcome back to the file.
        return BuiltAttempt(envelope, OutboxTag(OutboxTag.Kind.FILE_REQUEST, Uuid.random()))
    }

    override suspend fun afterEnqueue(id: FileId, built: BuiltAttempt) {
        store.setAttempt(id, built.tag.id)
    }

    override suspend fun attemptState(id: FileId): AttemptState {
        val row = store.findById(id) ?: return AttemptState.None
        val attemptId = row.attemptId ?: return AttemptState.None
        val queued = outbox.hasQueued(OutboxTag(OutboxTag.Kind.FILE_REQUEST, attemptId))
        return if (queued) AttemptState.Pending else AttemptState.None
    }

    override suspend fun resolveOutcome(outcome: OutboxOutcome): FileId? {
        if (outcome.tag.kind != OutboxTag.Kind.FILE_REQUEST) return null
        return store.findByAttemptId(outcome.tag.id)?.fileId
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
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Failed to emit request-unavailable event; the request is already dropped",
                throwable = e,
            )
        }
        return true
    }
}
