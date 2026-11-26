package org.yapyap.routing.file.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.fileControl
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What happened to an outgoing transfer; its owner (the seal registry or the relay forwards) reacts. */
internal sealed interface TransferEvent {
    val transfer: OutgoingTransfer

    /** The peer's coverage moved. */
    data class Progress(override val transfer: OutgoingTransfer) : TransferEvent

    /** The peer looks gone (no link, no ACK in time, or its link closed): a deposit trigger. */
    data class Gone(override val transfer: OutgoingTransfer) : TransferEvent

    /** The transfer ended; [OutgoingTransfer.status] says how. */
    data class Ended(override val transfer: OutgoingTransfer) : TransferEvent
}

/**
 * Runs every transfer this device sends (docs/envelope-redesign.md §5.3–§5.4): ours and relay
 * forwards alike, each one hop over its [ChunkSource]. It speaks only transfers — OFFER, a window
 * of chunk rows in the outbox, the peer's ACKs and CANCELs, expiry — and makes sure the peer ends
 * up holding every chunk the transfer describes. What a transfer is *for* is its owner's business:
 * the engine reports [events] and never decides about seals, deposits or relays.
 *
 * Durable state is the transfer rows plus the outbox; the window and the timers are rebuilt from
 * them, so a restart resumes and never re-sends what is still queued. Each transfer is handled
 * under its own lock; the events flow is unbounded, so reporting never waits on an owner.
 */
//TODO: logic seems ok, but messy, to be rewritten
internal class TransferEngine(
    private val ctx: RoutingContext,
    private val transfers: FileTransferStore,
    private val seals: FileSealStore,
    private val relays: FileRelayStore,
    private val plaintext: FilePlaintextSource,
    private val outbox: OutboxProcessor,
    private val outboxStore: PacketOutbox,
) {
    private val eventFlow = MutableSharedFlow<TransferEvent>(extraBufferCapacity = Int.MAX_VALUE)
    val events: SharedFlow<TransferEvent> = eventFlow.asSharedFlow()

    /** In-memory state of a live transfer, rebuilt from its rows when first touched. */
    private class Running(
        val source: ChunkSource,
        val queued: MutableSet<Int>,
        var lastProgressAt: Instant,
        var offerSentAt: Instant?,
        var goneReported: Boolean = false,
    )

    private val registryLock = Mutex()
    private val locks = mutableMapOf<TransferId, Mutex>()
    private val running = mutableMapOf<TransferId, Running>()

    /** Inserts [transfer] and sends its OFFER (re-armed by the tick if it can't go now). */
    suspend fun start(transfer: OutgoingTransfer) {
        transfers.insert(transfer)
        //TODO: untangle nested lambdas: we insert transfer, check some things and look it up in the db again, messy
        guarded("offer") { withLive(transfer.transferId) { current, run -> sendOffer(current, run) } }
    }

    /** Ends a live transfer at our end: tells the peer (CANCEL) and stops. */
    suspend fun cancel(transferId: TransferId) {
        withLive(transferId) { current, _ ->
            val cancel = FileHeader.Cancel(transferId, FileCancelReason.ABORT)
            outbox.enqueueAndWake(ctx.fileControl(current.peer, cancel, current.expiresAt), ctx.clock.now())
            end(current, FileTransferStatus.CANCELLED)
        }
    }

    /** True when [transferId] is a transfer we send (live or ended), so inbound control for it is ours. */
    suspend fun owns(transferId: TransferId): Boolean = transfers.find(transferId) != null

    /** The peer's coverage ACK, in the transfer's own chunk indices. */
    suspend fun onAck(transferId: TransferId, from: PeerId, highestContiguousIndex: Int) {
        withLive(transferId) { current, run ->
            if (from != current.peer) return@withLive
            val covered = minOf(highestContiguousIndex, current.shape.chunkCount - 1)
            if (covered <= current.contiguousAcked) return@withLive
            run.lastProgressAt = ctx.clock.now()
            val moved =
                current.copy(contiguousAcked = covered, nextChunkIndex = maxOf(current.nextChunkIndex, covered + 1))
            transfers.setProgress(moved.transferId, moved.nextChunkIndex, moved.contiguousAcked)
            eventFlow.emit(TransferEvent.Progress(moved))
            settle(moved, run)
        }
    }

    /** The peer ended the transfer at its end. */
    suspend fun onCancelled(transferId: TransferId, from: PeerId, reason: FileCancelReason) {
        withLive(transferId) { current, _ ->
            if (from != current.peer) return@withLive
            val status = when (reason) {
                FileCancelReason.DECLINED, FileCancelReason.NO_SPACE -> FileTransferStatus.DECLINED
                FileCancelReason.ABORT -> FileTransferStatus.ENDED
            }
            end(current, status)
        }
    }

    /** How one of our OFFER or chunk rows left the outbox. */
    suspend fun onOutcome(outcome: OutboxOutcome) {
        withLive(TransferId(outcome.tag.id)) { current, run ->
            val isOffer = outcome.packetId == current.offerPacketId
            when (outcome) {
                is OutboxOutcome.Acked -> {
                    run.lastProgressAt = ctx.clock.now()
                    if (isOffer) {
                        if (current.status == FileTransferStatus.OFFERED) {
                            transfers.setStatus(current.transferId, FileTransferStatus.STREAMING)
                        }
                        settle(current.copy(status = FileTransferStatus.STREAMING), run)
                    } else {
                        forgetChunk(current, run, outcome.packetId)
                        settle(current, run)
                    }
                }

                // DECLINED on the OFFER is the peer's refusal; on a chunk, or any other terminal
                // NACK, the transfer is gone at that end and ends quietly.
                is OutboxOutcome.Nacked -> end(
                    current,
                    if (isOffer && outcome.reason == PacketNackReason.DECLINED) FileTransferStatus.DECLINED
                    else FileTransferStatus.ENDED,
                )

                is OutboxOutcome.Expired -> when {
                    ctx.clock.now() >= current.expiresAt -> end(current, FileTransferStatus.EXPIRED)
                    isOffer -> sendOffer(current, run)
                    else -> {
                        val index = forgetChunk(current, run, outcome.packetId) ?: return@withLive
                        val rewound = current.copy(nextChunkIndex = minOf(current.nextChunkIndex, index))
                        transfers.setProgress(rewound.transferId, rewound.nextChunkIndex, rewound.contiguousAcked)
                        settle(rewound, run)
                    }
                }
            }
        }
    }

    /** Ticks every live transfer and reports transfers to a peer whose link just closed. */
    fun runIn(scope: CoroutineScope): Job = scope.launch {
        launch {
            ctx.peerLinks.linkDowns.collect { peer -> guarded("link-down") { onLinkDown(peer) } }
        }
        while (true) {
            guarded("tick") { tick() }
            delay(maxOf(ctx.routerConfig.value.chunkAckTimeout / 3, 5.seconds))
        }
    }

    private suspend fun onLinkDown(peer: PeerId) {
        for (transfer in transfers.live()) {
            if (transfer.peer != peer) continue
            withLive(transfer.transferId) { current, run ->
                if (current.status == FileTransferStatus.OFFERED || run.queued.isNotEmpty()) reportGone(current, run)
            }
        }
    }

    /**
     * Expiry, a lost OFFER re-armed, a stalled window refilled, and the gone signals: before the
     * OFFER lands, no link or no ACK within `relayBackupAfter` (the message triggers); after, no
     * link or no ACK at all for `chunkAckTimeout` while chunks are in flight — never one slow chunk.
     */
    private suspend fun tick() {
        val now = ctx.clock.now()
        val config = ctx.routerConfig.value
        for (transfer in transfers.live()) {
            withLive(transfer.transferId) { current, run ->
                if (now >= current.expiresAt) return@withLive end(current, FileTransferStatus.EXPIRED)
                val linked = ctx.peerLinks.linksOf(current.peer).isNotEmpty()
                when (current.status) {
                    FileTransferStatus.OFFERED -> {
                        val queued =
                            current.offerPacketId?.let { it in outboxStore.packetIdsByTag(tag(current)) } == true
                        if (!queued) sendOffer(current, run)
                        val sentAt = run.offerSentAt
                        if (!linked || (sentAt != null && now - sentAt > config.relayBackupAfter)) reportGone(
                            current,
                            run
                        )
                    }

                    else -> {
                        settle(current, run)
                        val silent = now - run.lastProgressAt > config.chunkAckTimeout
                        if (run.queued.isNotEmpty() && (!linked || silent)) reportGone(current, run)
                    }
                }
            }
        }
    }

    /** Refills the window and ends the transfer once the peer holds every chunk. */
    private suspend fun settle(transfer: OutgoingTransfer, run: Running) {
        if (transfer.status != FileTransferStatus.STREAMING) return
        val count = transfer.shape.chunkCount
        if (transfer.contiguousAcked >= count - 1) return end(transfer, FileTransferStatus.DONE)
        var next = maxOf(transfer.nextChunkIndex, transfer.contiguousAcked + 1)
        val window = ctx.routerConfig.value.maxInFlightChunks
        while (run.queued.size < window && next < count) {
            if (next in run.queued) {
                next += 1
                continue
            }
            val ciphertext = run.source.chunk(next)
                ?: return end(transfer.copy(nextChunkIndex = next), FileTransferStatus.ENDED)
            val envelope = ctx.envelope(
                packetType = PacketType.FILE,
                target = transfer.peer,
                routingHeader = FileHeader.Chunk(transfer.transferId, next).encode(),
                content = ciphertext,
                expiresAt = transfer.expiresAt,
                dispositionRequested = true,
            )
            outbox.enqueueAndWake(envelope, ctx.clock.now(), tag = tag(transfer), retryClass = RetryClass.FLAT)
            transfers.recordChunk(transfer.transferId, next, envelope.packetId)
            run.queued += next
            next += 1
        }
        if (next != transfer.nextChunkIndex) transfers.setProgress(transfer.transferId, next, transfer.contiguousAcked)
        // Every chunk queued and every queued chunk ACKed: the peer holds them all.
        if (next >= count && run.queued.isEmpty()) end(transfer.copy(nextChunkIndex = next), FileTransferStatus.DONE)
    }

    private suspend fun sendOffer(transfer: OutgoingTransfer, run: Running) {
        val offer = run.source.offer
        val envelope = ctx.envelope(
            packetType = PacketType.FILE,
            target = transfer.peer,
            routingHeader = FileHeader.Offer(transfer.transferId, offer.origin, offer.finalTarget, transfer.shape)
                .encode(),
            content = offer.sealBytes,
            expiresAt = transfer.expiresAt,
            dispositionRequested = true,
        )
        outbox.enqueueAndWake(envelope, ctx.clock.now(), tag = tag(transfer), retryClass = RetryClass.STANDARD)
        transfers.setOffer(transfer.transferId, envelope.packetId)
        run.offerSentAt = ctx.clock.now()
    }

    /** Drops a chunk row's mapping; returns its index, or null when it was not one of ours. */
    private suspend fun forgetChunk(transfer: OutgoingTransfer, run: Running, packetId: Uuid): Int? {
        val (owner, index) = transfers.chunkByPacket(packetId) ?: return null
        if (owner != transfer.transferId) return null
        run.queued -= index
        transfers.deleteChunk(packetId)
        return index
    }

    private suspend fun reportGone(transfer: OutgoingTransfer, run: Running) {
        if (run.goneReported) return
        run.goneReported = true
        eventFlow.emit(TransferEvent.Gone(transfer))
    }

    private suspend fun end(transfer: OutgoingTransfer, status: FileTransferStatus) {
        transfers.setStatus(transfer.transferId, status)
        outboxStore.deleteByTag(tag(transfer))
        transfers.deleteChunks(transfer.transferId)
        registryLock.withLock { running.remove(transfer.transferId) }?.source?.close()
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_TRANSFER_ENDED,
            message = "File transfer ended",
            fields = mapOf("transferId" to transfer.transferId, "peer" to transfer.peer, "status" to status),
        )
        eventFlow.emit(TransferEvent.Ended(transfer.copy(status = status)))
    }

    /**
     * Runs [block] under [transferId]'s lock with its current row and running state, only while
     * the transfer is live. A transfer whose source is gone (seal or relay row deleted) ends here.
     */
    private suspend fun withLive(
        transferId: TransferId,
        block: suspend (OutgoingTransfer, Running) -> Unit,
    ) {
        val lock = registryLock.withLock { locks.getOrPut(transferId) { Mutex() } }
        lock.withLock {
            val current = transfers.find(transferId)?.takeIf { it.status.isLive }
            if (current == null) {
                registryLock.withLock { locks.remove(transferId) }
                return
            }
            val state = registryLock.withLock { running[transferId] } ?: recover(current)
            if (state == null) {
                // Its seal or relay copy is gone: nothing left to send.
                end(current, FileTransferStatus.ENDED)
                return
            }
            block(current, state)
        }
    }

    /** Rebuilds a transfer's window from its chunk rows still queued in the outbox. */
    private suspend fun recover(transfer: OutgoingTransfer): Running? {
        val source = sourceFor(transfer) ?: return null
        val stillQueued = outboxStore.packetIdsByTag(tag(transfer)).toSet()
        val queued = mutableSetOf<Int>()
        for ((index, packetId) in transfers.chunks(transfer.transferId)) {
            if (packetId in stillQueued) queued += index else transfers.deleteChunk(packetId)
        }
        val now = ctx.clock.now()
        // A fresh start (also after a restart) is not evidence the peer is gone: clocks start now.
        val run = Running(source, queued, lastProgressAt = now, offerSentAt = transfer.offerPacketId?.let { now })
        registryLock.withLock { running[transfer.transferId] = run }
        return run
    }

    private suspend fun sourceFor(transfer: OutgoingTransfer): ChunkSource? {
        val relayTransferId = transfer.relayTransferId
        return if (relayTransferId != null) {
            relays.find(relayTransferId)?.let { RelayChunkSource(it, relays) }
        } else {
            seals.find(transfer.sealId)?.let {
                SealChunkSource(ctx.localDeviceId, it, transfer.shape, plaintext, ctx.envelopeProtections.file)
            }
        }
    }

    private fun tag(transfer: OutgoingTransfer) = OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transfer.transferId.value)

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "File transfer $what failed",
                throwable = e,
            )
        }
    }
}
