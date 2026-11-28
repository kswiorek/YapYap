package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.routing.candidate.AttemptState
import org.yapyap.routing.candidate.BuiltAttempt
import org.yapyap.routing.candidate.CandidateLedger
import org.yapyap.routing.candidate.CandidateStrategy
import org.yapyap.routing.candidate.OutboxAttemptStrategy
import org.yapyap.routing.candidate.SuccessAction
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePullAttemptStore
import org.yapyap.routing.store.FilePullClass
import org.yapyap.routing.store.OutboxTag
import kotlin.time.Instant

/** Small-path plug into the shared candidate core: coverage-built REQUESTs, ACK→WAITING. */
internal class FileRequestStrategy(
    private val ctx: RoutingContext,
    private val packets: FilePackets,
    private val pullAttempts: FilePullAttemptStore,
    private val requests: FileRequestSource,
    private val outbox: OutboxProcessor,
    private val events: MutableSharedFlow<FileEvent>,
) : CandidateStrategy<FileId>, OutboxAttemptStrategy<FileId> {
    override val ledger: CandidateLedger<FileId> = pullAttempts.candidateLedger(FilePullClass.SMALL)

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
            expiresAt = now + ctx.routerConfig.value.filePullAttemptTimeout,
        )
        return BuiltAttempt(envelope, OutboxTag.FileRequest)
    }

    override suspend fun afterEnqueue(id: FileId, built: BuiltAttempt) {
        // The request's packet id correlates its outbox outcome back to the file.
        pullAttempts.setRequestPacket(id, built.envelope.packetId)
    }

    override suspend fun attemptState(id: FileId): AttemptState {
        val row = pullAttempts.findById(id) ?: return AttemptState.None
        val packetId = row.requestPacketId ?: return AttemptState.None
        val queued = outbox.isQueued(packetId)
        return if (queued) AttemptState.Pending else AttemptState.None
    }

    override suspend fun resolveOutcome(outcome: OutboxOutcome): FileId? {
        if (outcome.tag != OutboxTag.FileRequest) return null
        return pullAttempts.findByRequestPacketId(outcome.packetId)?.fileId
    }

    override suspend fun onSuccess(id: FileId, device: PeerId): SuccessAction {
        // ACK ("I hold it, serving"): wait for the OFFER with a timeout; the reply is on its way.
        return SuccessAction.AwaitUntil(ctx.clock.now() + ctx.routerConfig.value.fileRequestWaitTimeout)
    }

    override suspend fun onExhausted(id: FileId): Boolean {
        ledger.drop(id)
        fileGuarded("Failed to emit request-unavailable event; the request is already dropped") {
            events.emit(FileEvent.RequestUnavailable(id))
        }
        return true
    }
}
