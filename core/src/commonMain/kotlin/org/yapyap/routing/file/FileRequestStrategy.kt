package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.routing.candidate.AttemptState
import org.yapyap.routing.candidate.BuiltAttempt
import org.yapyap.routing.candidate.CandidateAttemptStore
import org.yapyap.routing.candidate.CandidateStrategy
import org.yapyap.routing.candidate.SuccessAction
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePullAttemptStore
import org.yapyap.routing.store.FilePullClass
import org.yapyap.routing.store.OutboxTag
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Small-path plug into the shared candidate core: coverage-built REQUESTs, ACK→WAITING. */
internal class FileRequestStrategy(
    private val ctx: RoutingContext,
    private val packets: FilePackets,
    private val pullAttempts: FilePullAttemptStore,
    private val requests: FileRequestSource,
    private val outbox: OutboxProcessor,
    private val events: MutableSharedFlow<FileEvent>,
) : CandidateStrategy<FileId> {
    override val attempts: CandidateAttemptStore<FileId> = pullAttempts.candidateStore(FilePullClass.SMALL)

    override suspend fun candidateAccounts(id: FileId): List<AccountId> = requests.requestCandidates(id)

    override suspend fun idsWithCandidate(account: AccountId): List<FileId> =
        pullAttempts.idsOf(FilePullClass.SMALL).filter { id -> account in requests.requestCandidates(id) }

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
        val envelope = packets.request(
            device = device,
            sealedContent = ctx.envelopeProtections.file.sealRequest(request, device),
            expiresAt = now + ctx.routerConfig.value.fileRequestAttemptTimeout,
        )
        // The attempt's id correlates its outbox outcome back to the file.
        return BuiltAttempt(envelope, OutboxTag(OutboxTag.Kind.FILE_REQUEST, Uuid.random()))
    }

    override suspend fun afterEnqueue(id: FileId, built: BuiltAttempt) {
        pullAttempts.setAttempt(id, built.tag.id)
    }

    override suspend fun attemptState(id: FileId): AttemptState {
        val row = pullAttempts.findById(id) ?: return AttemptState.None
        val attemptId = row.attemptId ?: return AttemptState.None
        val queued = outbox.hasQueued(OutboxTag(OutboxTag.Kind.FILE_REQUEST, attemptId))
        return if (queued) AttemptState.Pending else AttemptState.None
    }

    override suspend fun resolveOutcome(outcome: OutboxOutcome): FileId? {
        if (outcome.tag.kind != OutboxTag.Kind.FILE_REQUEST) return null
        return pullAttempts.findByAttemptId(outcome.tag.id)?.fileId
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
