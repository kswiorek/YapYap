package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.routing.candidate.AttemptState
import org.yapyap.routing.candidate.BuiltAttempt
import org.yapyap.routing.candidate.CallOutcome
import org.yapyap.routing.candidate.CandidateAttemptStore
import org.yapyap.routing.candidate.CandidateStrategy
import org.yapyap.routing.candidate.SuccessAction
import org.yapyap.routing.file.large.LargeFileService
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.link.SessionOutcome
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePullAttemptStore
import org.yapyap.routing.store.FilePullClass
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Large-file plug into the shared candidate core (design §9, §6.6): the same
 * one-candidate-at-a-time loop as small REQUESTs, but each attempt opens a WebRTC
 * session and runs a fetch instead of enqueueing a packet. PULL_OK plays the ACK
 * and DECLINED the DECLINED, with no WAITING state — the answer and the bytes
 * come on the same channel. An unreachable candidate is not a decline: the request
 * stays PENDING until a candidate comes online. Online-but-unconnectable is reported
 * as "no direct connection" and never falls back to Tor.
 */
internal class LargeFileStrategy(
    private val ctx: RoutingContext,
    private val pullAttempts: FilePullAttemptStore,
    private val requests: FileRequestSource,
    private val webRtcLinks: LinkKeeper,
    private val fetchService: LargeFileService,
    private val events: MutableSharedFlow<FileEvent>,
) : CandidateStrategy<FileId> {
    override val attempts: CandidateAttemptStore<FileId> = pullAttempts.candidateStore(FilePullClass.LARGE)

    override suspend fun candidateAccounts(id: FileId): List<AccountId> = requests.requestCandidates(id)

    override suspend fun idsWithCandidate(account: AccountId): List<FileId> =
        pullAttempts.idsOf(FilePullClass.LARGE).filter { id -> account in requests.requestCandidates(id) }

    override suspend fun isWanted(id: FileId): Boolean = requests.heldCoverage(id) != null

    override suspend fun buildAttempt(id: FileId, device: PeerId, now: Instant): BuiltAttempt? = null

    override suspend fun afterEnqueue(id: FileId, built: BuiltAttempt) = Unit

    override suspend fun attemptState(id: FileId): AttemptState = AttemptState.None

    override suspend fun resolveOutcome(outcome: OutboxOutcome): FileId? = null

    override suspend fun onSuccess(id: FileId, device: PeerId): SuccessAction = SuccessAction.Done

    override suspend fun onExhausted(id: FileId): Boolean {
        attempts.drop(id)
        emitBestEffort(FileEvent.RequestUnavailable(id))
        return true
    }

    /**
     * One fetch attempt against [device]: open the session, then stream from the
     * checkpointed offset. The core records the attempt before calling, so a crash
     * mid-fetch never re-asks at once; every outcome below only steers the next pass.
     * Unexpected failures propagate: the core logs them and counts the attempt unanswered.
     */
    suspend fun runPull(id: FileId, device: PeerId): CallOutcome {
        // READY meanwhile: nothing to ask for.
        val coverage = requests.heldCoverage(id) ?: return CallOutcome.Satisfied
        val link = webRtcLinks.await(device, ctx.routerConfig.value.largeFileSessionOpenTimeout)
        if (link != SessionOutcome.Connected) {
            // Timeout opening the session: offline stays PENDING silently;
            // online-but-unconnectable is "no direct connection" (never Tor).
            if (ctx.peerLinks.linksOf(device).isNotEmpty()) {
                emitBestEffort(FileEvent.NoDirectConnection(id))
            }
            return CallOutcome.NoDirectConnection
        }
        return when (fetchService.fetch(device, id, coverage.contiguousBytes)) {
            LargeFileResult.SATISFIED -> {
                emitBestEffort(FileEvent.RequestSatisfied(id))
                CallOutcome.Satisfied
            }

            LargeFileResult.DECLINED -> CallOutcome.Declined
            // The receive ended locally (no space, declined, failed): drop the row, never
            // blacklist. No satisfaction event: the GUI learns how the receive ended from the engine.
            LargeFileResult.NO_SPACE, LargeFileResult.ENDED -> CallOutcome.Satisfied
            // A block failed its hash: back to PENDING in the usual candidate order.
            LargeFileResult.MISMATCH -> CallOutcome.Unanswered
            LargeFileResult.UNANSWERED -> CallOutcome.Unanswered
            LargeFileResult.NO_DIRECT_CONNECTION -> CallOutcome.NoDirectConnection
        }
    }

    /**
     * Best-effort event emission: a cancelled collector must tear the fetch down (rethrow),
     * while any other emission failure is logged and ignored (the attempt is already recorded).
     */
    private suspend fun emitBestEffort(event: FileEvent) {
        try {
            events.emit(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Failed to emit fetch event; the attempt is already recorded",
                throwable = e,
            )
        }
    }
}
