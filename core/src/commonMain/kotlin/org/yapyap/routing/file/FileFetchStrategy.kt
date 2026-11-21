package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.routing.candidate.AttemptState
import org.yapyap.routing.candidate.BuiltAttempt
import org.yapyap.routing.candidate.CallOutcome
import org.yapyap.routing.candidate.CandidateAttemptStore
import org.yapyap.routing.candidate.CandidateRow
import org.yapyap.routing.candidate.CandidateStrategy
import org.yapyap.routing.candidate.SuccessAction
import org.yapyap.routing.file.fetch.FileFetchManager
import org.yapyap.routing.file.fetch.FileFetchOutcome
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.outbound.SessionOutcome
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRequestAttemptStore
import org.yapyap.routing.store.FileRequestClass
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Large-path plug into the shared candidate core (design §9, §6.6): the same
 * one-candidate-at-a-time loop as small REQUESTs, but each attempt opens a WebRTC
 * session and runs a fetch instead of enqueueing a packet. FETCH_OK plays the ACK
 * and FETCH_DECLINED the DECLINED, with no WAITING state — the answer and the bytes
 * come on the same channel. An unreachable candidate is not a decline: the request
 * stays PENDING until a candidate comes online. Online-but-unconnectable is reported
 * as "no direct connection" and never falls back to Tor.
 */
internal class FileFetchStrategy(
    private val ctx: RoutingContext,
    private val store: FileRequestAttemptStore,
    private val requests: FileRequestSource,
    private val sessionOpener: ProactiveSessionOpener,
    private val fetchManager: FileFetchManager,
    private val events: MutableSharedFlow<FileEvent>,
) : CandidateStrategy<FileId> {
    private val dock = object : CandidateAttemptStore<FileId> {
        override suspend fun start(id: FileId, firstAttemptAt: Instant) =
            store.start(id, FileRequestClass.LARGE, firstAttemptAt)

        override suspend fun drop(id: FileId) = store.drop(id)
        override suspend fun earliestDueAt(): Instant? = store.earliestDueAt(FileRequestClass.LARGE)
        override suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<FileId>> =
            store.findDue(now, limit, FileRequestClass.LARGE)
                .map { CandidateRow(it.fileId, it.attempts, it.declinedDevices) }

        override suspend fun recordAttempt(id: FileId, nextAttemptAt: Instant) =
            store.recordAttempt(id, nextAttemptAt)

        override suspend fun postpone(id: FileId, nextAttemptAt: Instant) = store.postpone(id, nextAttemptAt)
        override suspend fun advanceToNow(ids: Collection<FileId>, now: Instant) = store.advanceToNow(ids, now)
        override suspend fun declinedDevices(id: FileId): Set<PeerId> = store.declinedDevices(id)
        override suspend fun addDeclined(id: FileId, device: PeerId) = store.addDeclined(id, device)
        override suspend fun removeDeclined(id: FileId, devices: Collection<PeerId>) =
            store.removeDeclined(id, devices)

        // The large path has no WAITING state (the answer and the bytes share a channel),
        // so the core never calls these on this dock.
        override suspend fun setWaiting(id: FileId, device: PeerId, until: Instant) = Unit
        override suspend fun clearWaiting(id: FileId) = Unit
    }

    override val attempts: CandidateAttemptStore<FileId> = dock

    override suspend fun candidateAccounts(id: FileId): List<AccountId> = requests.requestCandidates(id)

    // Same device first after a block mismatch (design §8/§9).
    override suspend fun preferredDevice(id: FileId): PeerId? = requests.preferDevice(id)

    override suspend fun idsWithCandidate(account: AccountId): List<FileId> =
        store.idsOf(FileRequestClass.LARGE).filter { id -> account in requests.requestCandidates(id) }

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
    suspend fun runFetch(id: FileId, device: PeerId): CallOutcome {
        // READY meanwhile: nothing to ask for.
        val coverage = requests.heldCoverage(id) ?: return CallOutcome.Satisfied
        val session = sessionOpener.awaitSession(device, ctx.routerConfig.value.fileSessionOpenTimeout)
        if (session != SessionOutcome.Connected) {
            // Timeout opening the session: offline stays PENDING silently;
            // online-but-unconnectable is "no direct connection" (never Tor).
            if (ctx.peerLinks.linksOf(device).isNotEmpty()) {
                emitBestEffort(FileEvent.NoDirectConnection(id))
            }
            return CallOutcome.NoDirectConnection
        }
        return when (fetchManager.fetchFile(device, id, coverage.contiguousBytes)) {
            is FileFetchOutcome.Satisfied -> {
                emitBestEffort(FileEvent.RequestSatisfied(id))
                CallOutcome.Satisfied
            }

            is FileFetchOutcome.Declined -> CallOutcome.Declined
            // Failed or interrupted (session dropped mid-file): PENDING, ask again on
            // backoff — preferably the same holder, which resumes from P (design §9).
            is FileFetchOutcome.Failed, is FileFetchOutcome.Interrupted -> CallOutcome.NoDirectConnection
        }
    }

    /**
     * Best-effort event emission: a cancelled collector must tear the fetch down (rethrow),
     * while any other emission failure is ignored (the attempt is already recorded).
     */
    private suspend fun emitBestEffort(event: FileEvent) {
        try {
            events.emit(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
