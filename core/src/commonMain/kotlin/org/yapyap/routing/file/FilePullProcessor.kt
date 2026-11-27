package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.routing.candidate.AttemptExecutor
import org.yapyap.routing.candidate.CandidateRequestCore
import org.yapyap.routing.file.large.LargeFileService
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePullAttemptStore
import org.yapyap.routing.store.FilePullClass
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * File pull loop (design §9): one candidate at a time.
 *
 * Small files: sealed REQUEST through the outbox, ACK, then WAITING for the
 * serve's OFFER; DECLINED moves to the next candidate. The sealed REQUEST is
 * direct-only — it never goes through a relay. Runs on the shared candidate
 * core with an outbox executor.
 *
 * Large files: the same one-candidate-at-a-time loop on the shared core, but the
 * attempt opens a WebRTC session and runs a fetch (no WAITING state, no outbox).
 * PULL_OK and DECLINED are the attempt's outcomes. An unreachable candidate
 * is not a decline — the request stays PENDING until a candidate comes online.
 * Online-but-unconnectable is "no direct connection" (never over Tor).
 */
internal class FilePullProcessor(
    ctx: RoutingContext,
    packets: FilePackets,
    private val pullAttempts: FilePullAttemptStore,
    private val requests: FileRequestSource,
    outboxProcessor: OutboxProcessor,
    peerPolicy: SyncPeerPolicy,
    maxIdlePoll: StateFlow<Duration>,
    private val events: MutableSharedFlow<FileEvent>,
    /** No candidate has an open link: whoever keeps links open may want to try these now (Tor, small path). */
    linkKeeper: LinkKeeper,
    /** The WebRTC keeper: a large file needs that link, so its core nudges it and awaits it. */
    webRtcLinks: LinkKeeper,
    fetchService: LargeFileService,
) {
    private val smallStrategy = FileRequestStrategy(ctx, packets, pullAttempts, requests, outboxProcessor, events)
    private val smallCore = CandidateRequestCore(
        ctx = ctx,
        strategy = smallStrategy,
        peerPolicy = peerPolicy,
        executor = AttemptExecutor.Outbox(outboxProcessor),
        maxIdlePoll = maxIdlePoll,
        linkKeeper = linkKeeper,
    )
    private val largeStrategy = LargeFileStrategy(ctx, pullAttempts, requests, webRtcLinks, fetchService, events)
    private val largeCore = CandidateRequestCore(
        ctx = ctx,
        strategy = largeStrategy,
        peerPolicy = peerPolicy,
        executor = AttemptExecutor.Call(
            ctx.routerConfig.map { it.maxConcurrentLargeFiles },
            largeStrategy::runPull,
        ),
        maxIdlePoll = maxIdlePoll,
        linkKeeper = webRtcLinks,
    )

    fun runIn(scope: CoroutineScope): Job = scope.launch {
        launch { smallCore.runIn(this) }
        launch { largeCore.runIn(this) }
    }

    fun wake() {
        smallCore.wake()
        largeCore.wake()
    }

    /** The loop that owns [fileId]'s row, or null when it is not tracked. */
    private suspend fun coreFor(fileId: FileId): CandidateRequestCore<FileId>? =
        when (pullAttempts.findById(fileId)?.pullClass) {
            FilePullClass.SMALL -> smallCore
            FilePullClass.LARGE -> largeCore
            null -> null
        }

    /** Starts asking for [fileId] (idempotent). The class comes from the total bytes;
     * an unknown total takes the small path (its REQUEST is harmless: holders DECLINE
     * above the limit and the class pins on first start). */
    suspend fun start(fileId: FileId, firstAttemptAt: Instant) {
        val total = requests.requestTotalBytes(fileId)
        val pullClass =
            if (total != null && total > SMALL_FILE_MAX_BYTES) FilePullClass.LARGE
            else FilePullClass.SMALL
        pullAttempts.start(fileId, pullClass, firstAttemptAt)
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
