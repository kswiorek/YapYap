package org.yapyap.routing.file.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.routing.file.FilePackets
import org.yapyap.routing.file.TransferEvent
import org.yapyap.routing.file.fileGuarded
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileSealStore
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.routing.store.OutgoingTransfer
import org.yapyap.routing.store.PacketOutbox
import kotlin.concurrent.Volatile
import kotlin.time.Instant

/**
 * Runs every transfer this device sends (docs/envelope-redesign.md §5.3–§5.4): ours and relay
 * forwards alike, each one hop over its [ChunkSource]. What a transfer is *for* is its owner's
 * business: the engine reports [events] and never decides about seals, deposits or relays.
 *
 * Each transfer runs as its own [OutgoingTransferSession] behind a mailbox; this class is only the
 * registry, the start-time recovery and the mux that feeds sessions their ACK/CANCEL/outbox/
 * link-down inputs. A slow transfer therefore blocks only itself. Durable state is the transfer
 * rows plus the outbox; recovery rebuilds sessions from them and never re-sends what is queued.
 */
internal class TransferEngine(
    private val ctx: RoutingContext,
    private val packets: FilePackets,
    private val transferStore: FileTransferStore,
    private val sealStore: FileSealStore,
    private val relayStore: FileRelayStore,
    private val plaintext: FilePlaintextSource,
    private val outbox: OutboxProcessor,
    private val outboxStore: PacketOutbox,
) {
    private val eventFlow = MutableSharedFlow<TransferEvent>(extraBufferCapacity = Int.MAX_VALUE)
    val events: SharedFlow<TransferEvent> = eventFlow.asSharedFlow()

    private val registryLock = Mutex()
    private val sessions = mutableMapOf<TransferId, OutgoingTransferSession>()

    /** The engine's coroutine scope; sessions started later run here. Set by [runIn]. */
    @Volatile
    private var sessionScope: CoroutineScope? = null

    /** Completes when the start-time recovery has rebuilt every live session. */
    private val recovered = CompletableDeferred<Unit>()

    /**
     * Inserts [transfer] and sends its OFFER (a re-arm is the session tick's job). The row and its
     * session appear together under the registry lock, so a [cancel] never sees one without the
     * other.
     */
    suspend fun start(transfer: OutgoingTransfer) {
        awaitRecovery()
        val session = registryLock.withLock {
            transferStore.insert(transfer)
            // The row was just inserted: its running state is fresh, no re-read or recovery needed.
            val source = sourceFor(transfer) ?: return@withLock null
            newSession(transfer, source, emptySet(), offerSentAt = null).also { sessions[it.transferId] = it }
        } ?: return finish(transfer.copy(status = FileTransferStatus.ENDED))
        session.begin()
        sessionScope?.let { scope -> registryLock.withLock { session.launch(scope, initialTick = false) } }
    }

    /**
     * Cancels [transferId] if it is still live. A running session is posted the cancel — it sends
     * the CANCEL and ends itself shortly, reported as [TransferEvent.Ended] like any other end; a
     * live row with no session (its recovery failed) is ended here at once. Never waits for the
     * session, so a hung transfer cannot stall its canceller.
     */
    suspend fun cancel(transferId: TransferId) {
        awaitRecovery()
        val orphan = registryLock.withLock {
            sessions[transferId]?.let { session ->
                session.cancel()
                return@withLock null
            }
            transferStore.find(transferId)?.takeIf { it.status.isLive }
        } ?: return
        finish(orphan.copy(status = FileTransferStatus.CANCELLED))
    }

    /** True when [transferId] is a transfer we send (live or ended), so inbound control for it is ours. */
    suspend fun owns(transferId: TransferId): Boolean = transferStore.find(transferId) != null

    /**
     * Posts the peer's coverage ACK, in the transfer's own chunk indices, to its session's
     * mailbox; the session applies it in its own loop.
     */
    suspend fun onAck(transferId: TransferId, from: PeerId, highestContiguousIndex: Int) {
        awaitRecovery()
        session(transferId)?.ack(from, highestContiguousIndex)
    }

    /** Posts the peer's cancellation to the transfer's session's mailbox. */
    suspend fun onCancelled(transferId: TransferId, from: PeerId, reason: FileCancelReason) {
        awaitRecovery()
        session(transferId)?.cancelled(from, reason)
    }

    /** Posts one of our OFFER/chunk outcomes to its transfer's session's mailbox. */
    suspend fun onOutcome(outcome: OutboxOutcome) {
        awaitRecovery()
        session(TransferId(outcome.tag.id))?.outcome(outcome)
    }

    /**
     * Starts the engine: rebuilds a session per live transfer from its rows plus the outbox,
     * launches every session, and re-routes link-downs. Recovery ends a transfer whose source is
     * gone; a recovered session is settled and checked at once.
     *
     * The mux waits on [recovered] before touching the registry, so a control packet or outbox
     * outcome that arrives while rows are being rebuilt is applied after recovery instead of
     * being dropped.
     */
    fun runIn(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        sessionScope = this
        try {
            guarded("recovery") { recover(this) }
        } finally {
            recovered.complete(Unit)
        }
        launch {
            ctx.peerLinks.linkDowns.collect { peer -> guarded("link-down") { onLinkDown(peer) } }
        }
    }

    private suspend fun recover(scope: CoroutineScope) {
        val known = registryLock.withLock { sessions.keys.toSet() }
        for (transfer in transferStore.live()) {
            if (transfer.transferId in known) continue
            guarded("recover ${transfer.transferId}") { recoverOne(transfer) }
        }
        registryLock.withLock { sessions.values.forEach { it.launch(scope, initialTick = true) } }
    }

    /** Rebuilds one session's window from its chunk rows still queued in the outbox. */
    private suspend fun recoverOne(transfer: OutgoingTransfer) {
        val source = sourceFor(transfer) ?: return finish(transfer.copy(status = FileTransferStatus.ENDED))
        val stillQueued = outboxStore.packetIdsByTag(transferTag(transfer.transferId)).toSet()
        val queued = mutableSetOf<Int>()
        for ((index, packetId) in transferStore.chunks(transfer.transferId)) {
            if (packetId in stillQueued) queued += index else transferStore.deleteChunk(packetId)
        }
        val now = ctx.clock.now()
        // A fresh start (also after a restart) is not evidence the peer is gone: clocks start now.
        val session = newSession(transfer, source, queued, offerSentAt = transfer.offerPacketId?.let { now })
        registryLock.withLock { sessions.getOrPut(transfer.transferId) { session } }
    }

    private fun newSession(
        transfer: OutgoingTransfer,
        source: ChunkSource,
        queued: Set<Int>,
        offerSentAt: Instant?,
    ) = OutgoingTransferSession(
        initial = transfer,
        source = source,
        initialQueued = queued,
        offerSentAt = offerSentAt,
        ctx = ctx,
        packets = packets,
        transferStore = transferStore,
        outbox = outbox,
        outboxStore = outboxStore,
        onEvent = eventFlow::emit,
        onEnded = ::finish,
    )

    private suspend fun onLinkDown(peer: PeerId) {
        // Copy under the lock, post outside it: a session's mailbox is a cheap trySend.
        val affected = registryLock.withLock { sessions.values.filter { it.peer == peer } }
        affected.forEach { it.linkDown() }
    }

    private suspend fun session(transferId: TransferId): OutgoingTransferSession? =
        registryLock.withLock { sessions[transferId] }

    /**
     * The startup barrier: before the recovery completes, a mux call waits instead of looking at a
     * half-built registry. Before [runIn] (tests that never start the engine) there is nothing to
     * wait for.
     */
    private suspend fun awaitRecovery() {
        if (sessionScope != null) recovered.await()
    }

    private suspend fun sourceFor(transfer: OutgoingTransfer): ChunkSource? {
        val relayTransferId = transfer.relayTransferId
        return if (relayTransferId != null) {
            relayStore.find(relayTransferId)?.let { RelayChunkSource(it, relayStore) }
        } else {
            sealStore.find(transfer.sealId)?.let {
                SealChunkSource(ctx.localDeviceId, it, transfer.shape, plaintext, ctx.envelopeProtections.file)
            }
        }
    }

    /**
     * Writes a transfer's end — [ended] carries its final status — and reports it: the session is
     * forgotten, the row takes the status, its queued packets and chunk rows go. Every end passes
     * here, from a session or for a row that never got one.
     */
    private suspend fun finish(ended: OutgoingTransfer) {
        registryLock.withLock { sessions.remove(ended.transferId) }
        transferStore.setStatus(ended.transferId, ended.status)
        outboxStore.deleteByTag(transferTag(ended.transferId))
        transferStore.deleteChunks(ended.transferId)
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_TRANSFER_ENDED,
            message = "File transfer ended",
            fields = mapOf("transferId" to ended.transferId, "peer" to ended.peer, "status" to ended.status),
        )
        eventFlow.emit(TransferEvent.Ended(ended))
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        fileGuarded("File transfer engine $what failed", block = block)
    }
}
