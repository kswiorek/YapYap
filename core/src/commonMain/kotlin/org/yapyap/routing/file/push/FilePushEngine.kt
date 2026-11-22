package org.yapyap.routing.file.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.ChunkRange
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.routing.file.FileIncomingTransfers
import org.yapyap.routing.file.FileTransferSignals
import org.yapyap.routing.file.fileChunkCount
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePush
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.FilePushStore
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.OutboxTag
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Per-transfer actor engine (design §6.1–§6.4): one [TransferActor] per live `file_pushes` row,
 * the window counting queued chunk rows. Replaces the shared-mutable pusher.
 */
internal class FilePushEngine(
    private val ctx: RoutingContext,
    private val protection: FileEnvelopeProtection,
    private val plaintext: FilePlaintextSource,
    private val pushes: FilePushStore,
    private val outbox: FileOutbox,
    private val events: MutableSharedFlow<FileEvent>,
    private val relaySelection: RelaySelectionPolicy,
    private val relay: FileRelayStore,
    /** Target-side rows, expired on the same tick as the push/relay sweeps. */
    private val incoming: FileIncomingTransfers,
    private val depositPolicy: FileDepositPolicy = FileDepositPolicy(pushes),
) : FileTransferSignals {
    private val mutex = Mutex()
    private val actors = mutableMapOf<TransferId, ActorEntry>()
    private val forwardActors = mutableMapOf<TransferId, ForwardEntry>()

    /** TransferIds with an actor currently starting: closes the check-and-register race. */
    private val starting = mutableSetOf<TransferId>()
    private val startingForwards = mutableSetOf<TransferId>()

    /**
     * The engine's own running coroutine while [runIn] runs, null otherwise. Actors run as its
     * children, so stopping the engine stops them; while stopped nothing starts — every row is
     * durable and the next [runIn] picks it up.
     */
    private var runScope: CoroutineScope? = null

    private data class ForwardEntry(val actor: ForwardActor, val job: Job)

    private data class ActorEntry(val actor: TransferActor, val job: Job)

    /** Starts actors for every live row (restart recovery: rebuilds windows, never re-seals). */
    suspend fun start() {
        val now = ctx.clock.now()
        for (row in pushes.live()) {
            if (now >= row.expiresAt) {
                expireRow(row)
                continue
            }
            try {
                startActor(row)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Failed to start push actor",
                    throwable = e,
                    fields = mapOf("transferId" to row.transferId),
                )
            }
        }
        dropTerminal(now)
        sweepForwards()
    }

    /** Terminal hygiene: terminal rows past their TTL go, with their key and outbox rows. */
    private suspend fun dropTerminal(now: Instant) {
        for (dead in pushes.terminalBefore(now)) {
            outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_PUSH, dead.transferId.value))
            pushes.delete(dead.transferId)
            removeActor(dead.transferId)
        }
    }

    /**
     * Runs the engine in a child scope of [scope], set synchronously so actors started right
     * after this call (pushes, serves, deposits) join it. Cancelling the returned job stops the
     * loop and every actor; the actor maps are cleared so a later [runIn] starts from the rows.
     */
    fun runIn(scope: CoroutineScope): Job {
        val engineJob = SupervisorJob(scope.coroutineContext[Job])
        val engineScope = CoroutineScope(scope.coroutineContext + engineJob)
        runScope = engineScope
        engineScope.launch {
            try {
                run()
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (runScope === engineScope) runScope = null
                        actors.clear()
                        forwardActors.clear()
                    }
                }
            }
        }
        return engineJob
    }

    private suspend fun CoroutineScope.run() {
        start()
        val linkDowns = launch {
            ctx.peerLinks.linkDowns.collect { peer ->
                try {
                    onLinkClosed(peer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Deposit on link close failed",
                        throwable = e,
                    )
                }
            }
        }
        try {
            while (true) {
                delay(maxOf(ctx.routerConfig.value.chunkAckTimeout / 3, 5.seconds))
                try {
                    tickAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Push tick failed",
                        throwable = e,
                    )
                }
            }
        } finally {
            linkDowns.cancel()
        }
    }

    /** Routes an outbox outcome to its actor by tag id. */
    suspend fun onOutboxOutcome(outcome: OutboxOutcome) {
        if (outcome.tag.kind != OutboxTag.Kind.FILE_PUSH && outcome.tag.kind != OutboxTag.Kind.FILE_FORWARD) return
        // No-link at claim time also trips the deposit: the outcome itself carries no link signal,
        // so the actor's tick checks link state. Outcomes only advance the window here.
        val id = TransferId(outcome.tag.id)
        val entry = mutex.withLock { actors[id] }
        if (entry != null) {
            entry.actor.mailbox.send(PushInput.Outcome(outcome))
        } else {
            // No actor: restart recovery may not have started it yet; start it if the row is live.
            val row = pushes.find(id)
            if (row != null && row.status.isLive && outcome.tag.kind == OutboxTag.Kind.FILE_PUSH) {
                startActor(row)
                mutex.withLock { actors[id] }?.actor?.mailbox?.send(PushInput.Outcome(outcome))
            }
            // Forward outcomes for unknown rows are the relay forwarder's (handled by FileRouting).
        }
    }

    override suspend fun coverageAck(
        transferId: TransferId,
        from: PeerId,
        highestContiguousIndex: Int,
        missing: List<ChunkRange>,
    ) {
        val row = pushes.find(transferId) ?: return
        if (!authorised(row, from)) {
            AppLog.debug(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Ignoring coverage ACK from unexpected source",
                fields = mapOf("transferId" to transferId, "from" to from),
            )
            return
        }
        mutex.withLock { actors[transferId] }?.actor?.mailbox?.send(
            PushInput.CoverageAck(highestContiguousIndex, missing),
        )
    }

    override suspend fun complete(transferId: TransferId, from: PeerId) {
        val row = pushes.find(transferId)
        if (row != null) {
            // Only the target authors COMPLETE (design §3.2): a relay holding a deposit never
            // does, so a COMPLETE from it must not resolve the direct push it never delivered.
            if (from != row.targetDevice) {
                AppLog.debug(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Ignoring COMPLETE from unexpected source",
                    fields = mapOf("transferId" to transferId, "from" to from),
                )
                return
            }
            // Any COMPLETE for (file, target device) resolves the authoring rows — including
            // one addressed to a deposit transferId from the ultimate target.
            val siblings = pushes.findLiveByFileAndTarget(row.fileId, row.targetDevice)
            var resolved = false
            for (sibling in siblings) {
                if (sibling.kind == FilePushKind.DEPOSIT) continue
                completeTransfer(sibling.transferId)
                resolved = true
            }
            if (!resolved) {
                // No live authoring row: resolve the addressed row itself (restart gap).
                completeTransfer(transferId)
            }
            return
        }
        // No push row: maybe a relay forward completing (forget after delivery).
        forwardComplete(transferId, from)
    }

    override suspend fun cancelled(transferId: TransferId, from: PeerId, reason: FileCancelReason) {
        val row = pushes.find(transferId)
        if (row != null) {
            // The relay of a deposit may refuse it, and the target may end its receive of
            // any of our transfers (design §3.2: target → author).
            if (!authorised(row, from) && from != row.targetDevice) {
                AppLog.debug(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Ignoring CANCEL from unexpected source",
                    fields = mapOf("transferId" to transferId, "from" to from),
                )
                return
            }
            mutex.withLock { actors[transferId] }?.actor?.mailbox?.send(PushInput.Cancelled(reason))
            return
        }
        // A target-declined forward drops the relay transfer (the only reachable refusal:
        // no space, since small files auto-accept and large files never touch relays).
        forwardCancelled(transferId, from)
    }

    /** User cancel: every live transfer and deposit for (file, device) ends. */
    suspend fun userCancel(fileId: FileId, device: PeerId) {
        for (row in pushes.findLiveByFileAndTarget(fileId, device)) {
            mutex.withLock { actors[row.transferId] }?.actor?.mailbox?.send(PushInput.UserCancel)
        }
    }

    /**
     * Supersede a live push with a fresh serve: CANCEL the old transferId (so the other end
     * drops its row instead of answering late chunks DECLINED), drop its rows, stay silent —
     * a handoff, not a failure, so no [FileEvent.Failed].
     */
    suspend fun supersede(fileId: FileId, device: PeerId) {
        for (row in pushes.findLiveByFileAndTarget(fileId, device)) {
            mutex.withLock { actors[row.transferId] }?.actor?.mailbox?.send(PushInput.SilentCancel)
        }
    }

    suspend fun onLinkClosed(peer: PeerId) {
        val live = pushes.live()
        for (row in live) {
            if (row.targetDevice != peer) continue
            if (row.kind != FilePushKind.DIRECT && row.kind != FilePushKind.SERVE) continue
            if (row.status != FilePushStatus.IN_FLIGHT) continue
            mutex.withLock { actors[row.transferId] }?.actor?.mailbox?.send(PushInput.LinkClosed)
        }
        // Forward actors never deposit (no chaining): nothing to do for FILE_FORWARD rows.
    }

    /** Starts a fresh actor for [row] (idempotent per transferId). */
    suspend fun startActor(row: FilePush): Boolean {
        mutex.withLock {
            if (actors.containsKey(row.transferId) || !starting.add(row.transferId)) return false
        }
        try {
            // Describe live: an OFFER is never sent for a file we do not hold.
            val info = try {
                plaintext.describe(row.fileId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "File push not started: descriptor unreadable; retried on next tick",
                    throwable = e,
                    fields = mapOf("transferId" to row.transferId),
                )
                return false
            }
            if (info == null) {
                // The file is no longer held (deleted, or a reference that moved): this transfer
                // can never send, so it ends now instead of showing as sending until its TTL.
                AppLog.warn(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_PUSH_ENDED,
                    message = "File push ended: the file is no longer held",
                    fields = mapOf("transferId" to row.transferId, "fileId" to row.fileId),
                )
                if (row.kind == FilePushKind.DEPOSIT) terminateSilent(row.transferId, FilePushStatus.ENDED)
                else failTransfer(row.transferId, FilePushStatus.ENDED, FileFailureReason.ENDED)
                return false
            }
            // Stopped: the row stays live and the next runIn starts it.
            val scope = runScope ?: return false
            val fixedSource =
                PlaintextChunkSource(ctx, protection, plaintext, { pushes.find(row.transferId) ?: row }, { info })
            val actor = TransferActor(
                ctx = ctx,
                protection = protection,
                pushes = pushes,
                outbox = outbox,
                events = events,
                row = row,
                source = fixedSource,
                engine = this,
                onTerminal = { finished -> removeActor(finished.transferId) },
                onDepositAsk = { ask -> depositForActor(ask) },
                tagKind = OutboxTag.Kind.FILE_PUSH,
            )
            // Recover runs before the actor reads its mailbox. Registered before it runs: an
            // outcome for its marker can arrive as soon as it is queued, and must find the actor.
            actor.recover()
            val job = scope.launch(start = CoroutineStart.LAZY) { actor.run() }
            mutex.withLock { actors[row.transferId] = ActorEntry(actor, job) }
            job.start()
            return true
        } finally {
            mutex.withLock { starting.remove(row.transferId) }
        }
    }

    private suspend fun accountOf(device: PeerId): AccountId? = ctx.devices.getAccountIdForDevice(device)

    /**
     * Drops the actor entry without self-cancel deadlock: when the caller is the actor's own
     * job (terminal paths call back into the engine), cancelling it would abort the
     * post-commit emit below, so only a different job is cancelled.
     */
    private suspend fun dropPushActor(id: TransferId) {
        val entry = mutex.withLock { actors.remove(id) } ?: return
        // The actor closes its plaintext stream in its own `finally`, never under a running read.
        if (entry.job != currentCoroutineContext()[Job]) entry.job.cancel()
    }

    private suspend fun removeActor(id: TransferId) = dropPushActor(id)

    /**
     * Atomically resolves one transfer as COMPLETED: persists the status and, exactly once per
     * (file, account), emits `OutgoingComplete`. Drops the actor. No-op when the row is already
     * terminal. Returns the resolved row, or null when it was already gone.
     */
    suspend fun completeTransfer(transferId: TransferId): FilePush? {
        data class Decision(val row: FilePush, val emit: FileEvent.OutgoingComplete?, val entry: ActorEntry?)

        val self = currentCoroutineContext()[Job]
        var stray: ActorEntry? = null
        val decision: Decision? = mutex.withLock {
            val row = pushes.find(transferId)
            if (row == null) {
                stray = actors.remove(transferId)
                return@withLock null
            }
            if (!row.status.isLive) {
                stray = actors.remove(transferId)
                return@withLock null
            }
            dropPushOutbox(transferId)
            pushes.deleteChunksByTransfer(transferId)
            pushes.setStatus(transferId, FilePushStatus.COMPLETED)
            val entry = actors.remove(transferId)
            var emit: FileEvent.OutgoingComplete? = null
            if (row.kind != FilePushKind.DEPOSIT) {
                val account = accountOf(row.targetDevice)
                val alreadyDone = account != null && pushes.allByFile(row.fileId).any { sibling ->
                    sibling.transferId != transferId && sibling.kind != FilePushKind.DEPOSIT &&
                            sibling.status == FilePushStatus.COMPLETED &&
                            accountOf(sibling.targetDevice) == account
                }
                if (!alreadyDone && account != null) {
                    emit = FileEvent.OutgoingComplete(row.fileId, account)
                }
            }
            Decision(row, emit, entry)
        }
        stray?.let { if (it.job != self) it.job.cancel() }
        if (decision == null) return null
        decision.entry?.let { if (it.job != self) it.job.cancel() }
        // COMPLETED rows are terminal, but the row is kept until TTL hygiene (tickAll):
        // the sibling gate above re-reads it.
        decision.emit?.let { events.emit(it) }
        if (decision.row.kind != FilePushKind.DEPOSIT) {
            cancelDepositsOf(decision.row)
        }
        return decision.row
    }

    /**
     * Atomically resolves one transfer terminally failed: persists the status and reports a
     * `Failed` only when every device transfer of the account is terminal and none completed
     * (design §§12/14). ENDED wins over EXPIRED when mixed. Drops the actor.
     */
    suspend fun failTransfer(transferId: TransferId, status: FilePushStatus, reason: FileFailureReason) {
        require(status.isTerminal) { "failTransfer() needs a terminal status" }
        data class Decision(val row: FilePush, val emit: FileEvent.Failed?, val entry: ActorEntry?)

        val self = currentCoroutineContext()[Job]
        var stray: ActorEntry? = null
        val decision: Decision? = mutex.withLock {
            val row = pushes.find(transferId)
            if (row == null) {
                stray = actors.remove(transferId)
                return@withLock null
            }
            if (!row.status.isLive) {
                stray = actors.remove(transferId)
                return@withLock null
            }
            dropPushOutbox(transferId)
            pushes.deleteChunksByTransfer(transferId)
            pushes.setStatus(transferId, status)
            val entry = actors.remove(transferId)
            var emit: FileEvent.Failed? = null
            if (row.kind != FilePushKind.DEPOSIT) {
                val account = accountOf(row.targetDevice)
                if (account != null) {
                    val siblings = pushes.allByFile(row.fileId).filter {
                        it.kind != FilePushKind.DEPOSIT && accountOf(it.targetDevice) == account
                    }
                    if (siblings.none { it.status.isLive } && siblings.none { it.status == FilePushStatus.COMPLETED }) {
                        val emitReason =
                            if (siblings.any { it.status == FilePushStatus.ENDED }) FileFailureReason.ENDED
                            else reason
                        emit = FileEvent.Failed(row.fileId, account, emitReason)
                    }
                }
            }
            Decision(row, emit, entry)
        }
        stray?.let { if (it.job != self) it.job.cancel() }
        decision?.entry?.let { if (it.job != self) it.job.cancel() }
        decision?.emit?.let { events.emit(it) }
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_PUSH_ENDED,
            message = "File transfer ended",
            fields = mapOf("transferId" to transferId, "status" to status),
        )
    }

    /** Atomically resolves one transfer terminally without reporting it. Drops the actor. */
    suspend fun terminateSilent(transferId: TransferId, status: FilePushStatus) {
        require(status.isTerminal) { "terminateSilent() needs a terminal status" }
        val self = currentCoroutineContext()[Job]
        val entry: ActorEntry? = mutex.withLock {
            val row = pushes.find(transferId) ?: return@withLock actors.remove(transferId)
            if (!row.status.isLive) {
                return@withLock actors.remove(transferId)
            }
            dropPushOutbox(transferId)
            pushes.deleteChunksByTransfer(transferId)
            pushes.setStatus(transferId, status)
            actors.remove(transferId)
        }
        entry?.let { if (it.job != self) it.job.cancel() }
    }

    private suspend fun dropPushOutbox(transferId: TransferId) {
        outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_PUSH, transferId.value))
    }

    private suspend fun depositForActor(ask: TransferActor) {
        val row = pushes.find(ask.transferId) ?: return
        depositRemainder(row.transferId)
    }

    private suspend fun cancelDepositsOf(done: FilePush) {
        for (other in pushes.findLiveByFileAndTarget(done.fileId, done.targetDevice)) {
            if (other.transferId == done.transferId) continue
            if (other.kind != FilePushKind.DEPOSIT) continue
            val entry = mutex.withLock { actors[other.transferId] }
            if (entry != null) {
                // Politely CANCEL outstanding deposits so relays free quota at once.
                entry.actor.mailbox.send(PushInput.UserCancel)
            } else {
                // No actor (inserted but never started, or restart gap): end it directly so
                // it never lingers to EXPIRED instead of CANCELLED.
                terminateSilent(other.transferId, FilePushStatus.CANCELLED)
            }
        }
    }

    private suspend fun expireRow(row: FilePush) {
        // Gated like any other terminal end: a sibling still sending, or an already
        // completed send, suppresses the report.
        if (row.kind == FilePushKind.DEPOSIT) terminateSilent(row.transferId, FilePushStatus.EXPIRED)
        else failTransfer(row.transferId, FilePushStatus.EXPIRED, FileFailureReason.EXPIRED)
    }

    private suspend fun tickAll() {
        val now = ctx.clock.now()
        // Expire live rows past expires_at.
        for (row in pushes.live()) {
            if (now >= row.expiresAt) {
                val entry = mutex.withLock { actors[row.transferId] }
                if (entry != null) {
                    entry.actor.mailbox.send(PushInput.Tick)
                } else {
                    expireRow(row)
                }
            } else {
                // Ensure every live row has an actor (restart gap), then tick it.
                val hasActor = mutex.withLock { actors.containsKey(row.transferId) }
                if (!hasActor) {
                    try {
                        startActor(row)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.error(
                            component = LogComponent.FILE,
                            event = LogEvent.FILE_TASK_FAILED,
                            message = "Failed to restart push actor on tick",
                            throwable = e,
                            fields = mapOf("transferId" to row.transferId),
                        )
                    }
                }
                mutex.withLock { actors[row.transferId] }?.actor?.mailbox?.send(PushInput.Tick)
            }
        }
        // No-link-at-claim deposit: a live IN_FLIGHT row whose target has no open link deposits.
        for (row in pushes.live()) {
            if (row.kind != FilePushKind.DIRECT && row.kind != FilePushKind.SERVE) continue
            if (row.status != FilePushStatus.IN_FLIGHT) continue
            if (ctx.peerLinks.linksOf(row.targetDevice).isEmpty()) {
                val entry = mutex.withLock { actors[row.transferId] } ?: continue
                if (entry.actor.hasQueuedSync()) {
                    depositRemainder(row.transferId)
                }
            }
        }
        dropTerminal(now)
        // Incoming rows end with their transfer: sweep expired ones on the same tick.
        try {
            incoming.expire(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Incoming transfer sweep failed",
                throwable = e,
            )
        }
        // Relay forwards: expiry, idle reservations, WHOLE promotion, restart rebuild.
        try {
            sweepForwards()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Relay forward sweep failed",
                throwable = e,
            )
        }
    }

    /**
     * Deposit the remainder of [transferId]: one round, one transfer per relay. Relays are
     * selected before the `deposited_at` claim, so an empty selection (or a selection
     * failure) never burns the single round. Claims `deposited_at` first so ten ticks and
     * a restart still deposit once.
     */
    suspend fun depositRemainder(transferId: TransferId): Int {
        val row = pushes.find(transferId) ?: return 0
        if (!row.status.isLive) return 0
        if (row.kind != FilePushKind.DIRECT && row.kind != FilePushKind.SERVE) return 0
        val count = fileChunkCount(row.totalBytes, row.chunkSizeBytes)
        val resumeFrom = maxOf(row.startIndex, row.contiguousAcked + 1)
        if (resumeFrom >= count) return 0
        val relays = relaySelection.selectRelays(row.targetDevice)
        if (relays.isEmpty()) return 0
        if (!depositPolicy.claimOnce(transferId, ctx.clock.now())) return 0
        var started = 0
        for (relay in relays) {
            val now = ctx.clock.now()
            val deposit = FilePush(
                transferId = TransferId(Uuid.random()),
                fileId = row.fileId,
                targetDevice = row.targetDevice,
                viaRelay = relay,
                kind = FilePushKind.DEPOSIT,
                totalBytes = row.totalBytes,
                key = protection.generateTransferKey(),
                chunkSizeBytes = row.chunkSizeBytes,
                startIndex = resumeFrom,
                nextChunkIndex = resumeFrom,
                contiguousAcked = resumeFrom - 1,
                markerPacketId = null,
                status = FilePushStatus.CREATED,
                createdAt = now,
                expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
            )
            pushes.insert(deposit)
            // A COMPLETE (or any end) may have landed while the relays were being chosen. Once
            // the deposit row exists, an end that lands later cancels it with the other
            // deposits; one that landed before must be checked here.
            if (pushes.find(transferId)?.status?.isLive != true) {
                terminateSilent(deposit.transferId, FilePushStatus.CANCELLED)
                break
            }
            if (startActor(deposit)) started += 1
        }
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_DEPOSITED,
            message = "Deposited file remainder at relays",
            fields = mapOf("transferId" to transferId, "relays" to relays.size),
        )
        return started
    }

    private fun authorised(row: FilePush, from: PeerId): Boolean {
        val expected = row.viaRelay ?: row.targetDevice
        return from == expected
    }

    // -- Relay forwards (design §7.2.4): the same pusher over a DB chunk source -------------

    /** The relay door saw [transferId] become WHOLE: start its forward (no sweep, no ticks). */
    suspend fun onRelayWhole(transferId: TransferId) {
        ensureForward(transferId, tickExisting = false)
    }

    /**
     * The tick's relay pass: expiry, idle reservations, WHOLE promotion backstop, restart
     * rebuild, and one Tick per running forward (restreams). Never run per inbound chunk.
     */
    suspend fun sweepForwards() {
        val store = relay
        val now = ctx.clock.now()
        val idleTtl = ctx.routerConfig.value.fileRelayIdleTtl
        val live = store.live()
        // The relay door deletes rows on its own (eviction, CANCEL, expiry at a chunk): a forward
        // actor whose row is gone would wait for input forever, so it is reaped here.
        val liveIds = live.map { it.transferId }.toSet()
        val orphans = mutex.withLock { forwardActors.keys.filter { it !in liveIds && it !in startingForwards } }
        // Re-checked one by one: a row admitted after the snapshot is not an orphan.
        for (id in orphans) if (store.find(id) == null) dropForwardActor(id)
        for (row in live) {
            if (now >= row.expiresAt) {
                dropForward(row.transferId)
                continue
            }
            when (row.status) {
                FileRelayStatus.RESERVED -> {
                    // Backstop promotion (concurrent chunk stores can both read a pre-final
                    // count): once the suffix is provably complete, mark WHOLE.
                    if (store.promoteIfWhole(row.transferId)) {
                        ensureForward(row.transferId, tickExisting = true)
                    } else if (row.lastChunkAt + idleTtl <= now) {
                        // Idle reservation TTL (design §7.2.3): a partial transfer that never
                        // completed frees its quota; the pull path covers the rest.
                        dropForward(row.transferId)
                    }
                }

                FileRelayStatus.WHOLE,
                FileRelayStatus.FORWARDING,
                FileRelayStatus.STREAMING,
                    -> ensureForward(row.transferId, tickExisting = true)
            }
        }
    }

    /**
     * One forward actor per relay transfer: check-and-register under [mutex] with a
     * [startingForwards] guard, so the door and the tick sweep can never both start one.
     */
    private suspend fun ensureForward(id: TransferId, tickExisting: Boolean) {
        val existing = mutex.withLock {
            forwardActors[id]?.actor ?: run {
                if (!startingForwards.add(id)) return
                null
            }
        }
        if (existing != null) {
            // The tick restreams through existing actors; the door never ticks them.
            if (tickExisting) existing.mailbox.trySend(ForwardInput.Tick)
            return
        }
        try {
            val scope = runScope ?: return
            if (relay.find(id) == null) return
            val actor = ForwardActor(ctx, relay, outbox, id) { finished -> dropForwardActor(finished) }
            // Registered before its marker goes out, so the marker's outcome finds the actor.
            val job = scope.launch(start = CoroutineStart.LAZY) { actor.run() }
            mutex.withLock { forwardActors[id] = ForwardEntry(actor, job) }
            try {
                actor.recoverAndStart()
            } catch (e: Throwable) {
                // Never leave a registered actor that will not run.
                mutex.withLock { if (forwardActors[id]?.job === job) forwardActors.remove(id) }
                job.cancel()
                throw e
            }
            job.start()
        } finally {
            mutex.withLock { startingForwards.remove(id) }
        }
    }

    private suspend fun dropForward(id: TransferId) {
        // Relay rows first, then outbox rows (documented order): a crash in between leaves
        // outbox rows whose outcome the engine ignores because no actor exists.
        relay.delete(id)
        outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_FORWARD, id.value))
        val self = currentCoroutineContext()[Job]
        val entry = mutex.withLock { forwardActors.remove(id) }
        if (entry != null && entry.job != self) entry.job.cancel()
    }

    /** Drops a forward actor without self-cancel deadlock (call-time job comparison). */
    private suspend fun dropForwardActor(id: TransferId) {
        val entry = mutex.withLock { forwardActors.remove(id) } ?: return
        if (entry.job != currentCoroutineContext()[Job]) entry.job.cancel()
    }

    /** Routes a FILE_FORWARD outbox outcome to its forward actor. */
    suspend fun onForwardOutcome(outcome: OutboxOutcome) {
        if (outcome.tag.kind != OutboxTag.Kind.FILE_FORWARD) return
        val id = TransferId(outcome.tag.id)
        val actor = mutex.withLock { forwardActors[id]?.actor }
        if (actor != null) {
            actor.mailbox.send(ForwardInput.Outcome(outcome))
        } else {
            // No actor yet (restart, or the outcome arrived before the sweep): ensure one
            // if the relay row is live, then route.
            val row = relay.find(id) ?: return
            if (row.status == FileRelayStatus.WHOLE ||
                row.status == FileRelayStatus.FORWARDING ||
                row.status == FileRelayStatus.STREAMING
            ) {
                ensureForward(id, tickExisting = false)
                mutex.withLock { forwardActors[id]?.actor }?.mailbox?.send(ForwardInput.Outcome(outcome))
            }
        }
    }

    private suspend fun forwardComplete(transferId: TransferId, from: PeerId) {
        val row = relay.find(transferId) ?: return
        if (from != row.targetDevice) {
            AppLog.debug(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Ignoring forward COMPLETE from unexpected source",
                fields = mapOf("transferId" to transferId, "from" to from),
            )
            return
        }
        val actor = mutex.withLock { forwardActors[transferId]?.actor }
        if (actor != null) actor.mailbox.send(ForwardInput.Complete)
        else dropForward(transferId)
    }

    private suspend fun forwardCancelled(transferId: TransferId, from: PeerId) {
        val row = relay.find(transferId) ?: return
        if (from != row.targetDevice) return
        val actor = mutex.withLock { forwardActors[transferId]?.actor }
        if (actor != null) actor.mailbox.send(ForwardInput.Cancelled)
        else dropForward(transferId)
    }
}
