package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
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
import org.yapyap.routing.file.fetch.FileFetchManager
import org.yapyap.routing.file.fetch.FileFetchOutcome
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.outbound.SessionOutcome
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRequestAttemptStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.sync.BuiltAttempt
import org.yapyap.routing.sync.CandidateAttemptStore
import org.yapyap.routing.sync.CandidateRequestCore
import org.yapyap.routing.sync.CandidateRow
import org.yapyap.routing.sync.CandidateStrategy
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * File request loop (design §9): one candidate at a time.
 *
 * Small files: sealed REQUEST through the outbox, ACK, then WAITING for the
 * OFFER; DECLINED moves to the next candidate. The sealed REQUEST is
 * direct-only — it never goes through a relay. Runs on the shared candidate
 * core ([CandidateRequestCore], step 15).
 *
 * Large files (step 22): the same one-candidate-at-a-time loop, but the
 * attempt opens a WebRTC session through [ProactiveSessionOpener.awaitSession]
 * (timeout `fileSessionOpenTimeout`) and then runs a fetch (step 21).
 * FETCH_OK and FETCH_DECLINED are the attempt's outcomes; there is no WAITING
 * state because the answer and the bytes come on the same channel. An
 * unreachable candidate is not a decline — the request stays PENDING until a
 * candidate comes online. A candidate that is online but whose session cannot
 * be made is reported as "no direct connection" (never over Tor).
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
    sessionOpener: ProactiveSessionOpener? = null,
    fetchManager: FileFetchManager? = null,
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
    private val largeLoop = if (sessionOpener != null && fetchManager != null) {
        FileLargeRequestLoop(ctx, attempts, gate, peerPolicy, maxIdlePoll, events, onNoLink, sessionOpener, fetchManager)
    } else {
        null
    }

    init {
        strategy.core = core
    }

    fun runIn(scope: CoroutineScope): Job = scope.launch {
        launch { core.runIn(this) }
        largeLoop?.let { launch { it.runIn(this) } }
    }

    fun wake() {
        core.wake()
        largeLoop?.wake()
    }

    /** Starts asking for [fileId] (idempotent). */
    suspend fun start(fileId: FileId, firstAttemptAt: Instant) {
        attempts.start(fileId, firstAttemptAt)
        core.wake()
        largeLoop?.wake()
    }

    /** How one attempt's outbox entry ended (small path only; large never touches the outbox). */
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

/** Small-path plug into the shared candidate core: coverage-built REQUESTs, ACK→WAITING. */
internal class FileRequestStrategy(
    private val ctx: RoutingContext,
    private val store: FileRequestAttemptStore,
    private val gate: FileIngestPort,
    private val outbox: OutboxProcessor,
    private val events: MutableSharedFlow<FileEvent>,
) : CandidateStrategy<FileId> {
    /**
     * Back-reference for WAITING-timeout deprioritisation (see `isAttemptPending`).
     * Assigned in the processor's init block, so the strategy is fully wired before
     * the processor finishes constructing (no post-construction patching).
     */
    internal lateinit var core: CandidateRequestCore<FileId>

    private val dock = object : CandidateAttemptStore<FileId> {
        override suspend fun start(id: FileId, firstAttemptAt: Instant) = store.start(id, firstAttemptAt)
        override suspend fun drop(id: FileId) = store.drop(id)
        override suspend fun earliestDueAt(): Instant? = store.earliestDueAt()
        override suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<FileId>> =
            store.findDue(now, limit)
                .filter { isSmall(it.fileId) }
                .map { CandidateRow(it.fileId, it.attempts, it.declinedDevices) }

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
        // Which tracked small files list this account as a candidate. Few live
        // requests; correctness over an index. Large files run their own loop.
        return store.allIds().filter { id ->
            isSmall(id) && account in gate.requestCandidates(id)
        }
    }

    private suspend fun isSmall(id: FileId): Boolean {
        val total = try {
            gate.requestTotalBytes(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return total == null || total <= SMALL_FILE_MAX_BYTES
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

/**
 * Large-path request loop (step 22): one candidate at a time, session first.
 * The attempt opens a WebRTC session through [ProactiveSessionOpener.awaitSession]
 * (timeout `fileSessionOpenTimeout`), then runs a fetch (step 21). FETCH_OK and
 * FETCH_DECLINED are the attempt's outcomes; there is no WAITING state.
 * An unreachable candidate is not a decline — the request stays PENDING until a
 * candidate comes online. Online-but-unconnectable is "no direct connection".
 */
internal class FileLargeRequestLoop(
    private val ctx: RoutingContext,
    private val store: FileRequestAttemptStore,
    private val gate: FileIngestPort,
    private val peerPolicy: SyncPeerPolicy,
    private val maxIdlePoll: StateFlow<Duration>,
    private val events: MutableSharedFlow<FileEvent>,
    private val onNoLink: suspend (PeerId) -> Unit,
    private val sessionOpener: ProactiveSessionOpener,
    private val fetchManager: FileFetchManager,
) {
    private val wakeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun wake() {
        wakeSignal.tryEmit(Unit)
    }

    fun runIn(scope: CoroutineScope): Job {
        scope.launch {
            ctx.peerLinks.linkUps.collect { up ->
                runCatching { onLinkUp(up.peer) }
                wake()
            }
        }
        return scope.launch {
            while (true) {
                try {
                    processDue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.ROUTER,
                        event = LogEvent.SYNC_PROCESS_FAILED,
                        message = "Large file request pass failed",
                        throwable = e,
                    )
                }
                withTimeoutOrNull(maxIdlePoll.value) {
                    wakeSignal.first()
                }
            }
        }
    }

    private suspend fun processDue() {
        val now = ctx.clock.now()
        val due = try {
            store.findDue(now, 10)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        for (row in due) {
            if (!isLarge(row.fileId)) continue
            processOne(row.fileId, row.attempts, row.declinedDevices, now)
        }
    }

    private suspend fun processOne(fileId: FileId, attempts: Int, declined: Set<PeerId>, now: Instant) {
        val coverage = try {
            gate.heldCoverage(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: run {
            // Already READY: nothing to ask for.
            runCatching { store.drop(fileId) }
            return
        }
        val accounts = try {
            gate.requestCandidates(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val candidates = try {
            ctx.devices.getAllPeerDevicesForAccounts(accounts)
                .filter { it != ctx.localDeviceId }.distinct()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        // Resolve Tor endpoints up front (binds PeerLinks' device→endpoint map
        // so link checks below see this run's links, not just previously bound ones).
        for (device in candidates) {
            runCatching { ctx.peerLinks.linksOf(device) }
        }
        val nextDevice = peerPolicy.pickNextDevice(candidates, declined)
        if (nextDevice == null) {
            if (candidates.isNotEmpty() && candidates.all { it in declined }) {
                // Every candidate refused: terminal until a user re-opens (design §14).
                runCatching { events.emit(FileEvent.RequestUnavailable(fileId)) }
                runCatching { store.drop(fileId) }
                return
            }
            // No sendable candidate (none linked): PENDING, not dead. Nudge links
            // so an offline holder wakes us when it comes online.
            candidates.filterNot { it in declined }.forEach { runCatching { onNoLink(it) } }
            runCatching { store.recordAttempt(fileId, now + backoff(attempts)) }
            return
        }
        val outcome = try {
            sessionOpener.awaitSession(nextDevice, ctx.routerConfig.value.fileSessionOpenTimeout)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SessionOutcome.Timeout
        }
        if (outcome != SessionOutcome.Connected) {
            // Timeout opening the session: offline stays PENDING silently;
            // online-but-unconnectable is "no direct connection" (never Tor).
            val linked = try {
                ctx.peerLinks.linksOf(nextDevice).isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (linked) {
                runCatching { events.emit(FileEvent.NoDirectConnection(fileId)) }
            }
            runCatching { store.recordAttempt(fileId, now + backoff(attempts)) }
            return
        }
        val fromOffset = coverage.contiguousBytes
        val fetchOutcome = try {
            fetchManager.fetchFile(nextDevice, fileId, fromOffset)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FileFetchOutcome.Interrupted
        }
        when (fetchOutcome) {
            is FileFetchOutcome.Satisfied -> {
                runCatching { store.drop(fileId) }
                runCatching { events.emit(FileEvent.RequestSatisfied(fileId)) }
            }
            is FileFetchOutcome.Declined -> {
                runCatching { store.addDeclined(fileId, nextDevice) }
                runCatching { store.advanceToNow(listOf(fileId), ctx.clock.now()) }
            }
            is FileFetchOutcome.Failed, is FileFetchOutcome.Interrupted -> {
                runCatching { store.recordAttempt(fileId, ctx.clock.now() + backoff(attempts)) }
            }
        }
        wake()
    }

    /** A link came up: ids this account can answer are asked now, whatever their backoff. */
    private suspend fun onLinkUp(deviceId: PeerId) {
        val account = try {
            ctx.devices.getAccountIdForDevice(deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val ids = try {
            store.allIds().filter { id -> account in gate.requestCandidates(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        runCatching { store.advanceToNow(ids, ctx.clock.now()) }
    }

    private suspend fun isLarge(fileId: FileId): Boolean {
        val total = try {
            gate.requestTotalBytes(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        return total > SMALL_FILE_MAX_BYTES
    }

    private fun backoff(attempts: Int): Duration {
        val shift = attempts.coerceIn(0, 7)
        return (30L * (1L shl shift)).coerceAtMost(3600L).seconds
    }
}
