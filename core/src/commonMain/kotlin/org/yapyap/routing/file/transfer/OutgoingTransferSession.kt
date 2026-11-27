package org.yapyap.routing.file.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FilePackets
import org.yapyap.routing.file.TransferEvent
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.OutgoingTransfer
import org.yapyap.routing.store.PacketOutbox
import org.yapyap.routing.store.RetryClass
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One outgoing transfer, run as its own actor (docs/envelope-redesign.md §5.3–§5.4): it owns the
 * transfer row copy, its [ChunkSource], the queued chunk window, the offer/progress clocks and the
 * gone flag, and serialises every operation on that state through its mailbox. A slow plaintext
 * read or store write blocks only this transfer; the engine's mux stays free for everyone else.
 *
 * The engine owns the registry and recovery; this class only runs the transfer, ends it, and
 * reports [TransferEvent]s to its owner. Durable state stays the transfer rows plus the outbox.
 */
internal class OutgoingTransferSession(
    initial: OutgoingTransfer,
    private val source: ChunkSource,
    private val queued: MutableSet<Int>,
    private var lastProgressAt: Instant,
    private var offerSentAt: Instant?,
    private val ctx: RoutingContext,
    private val packets: FilePackets,
    private val transferStore: FileTransferStore,
    private val outbox: OutboxProcessor,
    private val outboxStore: PacketOutbox,
    private val emitEvent: suspend (TransferEvent) -> Unit,
    private val onEnded: suspend (TransferId) -> Unit,
) {
    private sealed interface SessionMsg {
        /** The loop's idle timeout: the periodic per-transfer tick. */
        data object Tick : SessionMsg
        data class Ack(val from: PeerId, val highestContiguousIndex: Int) : SessionMsg
        data class Cancelled(val from: PeerId, val reason: FileCancelReason) : SessionMsg
        data class Outcome(val outcome: OutboxOutcome) : SessionMsg
        data object LinkDown : SessionMsg

        /** Our own cancel: tell the peer and end as CANCELLED. */
        data object Cancel : SessionMsg
    }

    private val mailbox = Channel<SessionMsg>(Channel.UNLIMITED)
    private var transfer = initial
    private var ended = false
    private var started = false
    private var goneReported = false

    /** Identity facts, captured once: the engine reads them from its threads. */
    val transferId: TransferId = initial.transferId
    val peer: PeerId = initial.peer

    /**
     * Starts the loop as a child of [scope]. [initialTick] reproduces the engine's first pass at
     * start: unlike a transfer started afterwards, a recovered (or pre-start) one is settled and
     * checked at once.
     */
    fun launch(scope: CoroutineScope, initialTick: Boolean) {
        if (started) return
        started = true
        scope.launch { run(initialTick) }
    }

    /**
     * Sends the first OFFER on the caller's coroutine. Called once by the engine for a fresh
     * transfer, before the loop starts, so [TransferEngine.start] returns with the OFFER queued.
     */
    suspend fun begin() {
        guarded("offer") { sendOffer() }
    }

    fun ack(from: PeerId, highestContiguousIndex: Int) {
        mailbox.trySend(SessionMsg.Ack(from, highestContiguousIndex))
    }

    fun cancelled(from: PeerId, reason: FileCancelReason) {
        mailbox.trySend(SessionMsg.Cancelled(from, reason))
    }

    fun outcome(outcome: OutboxOutcome) {
        mailbox.trySend(SessionMsg.Outcome(outcome))
    }

    fun linkDown() {
        mailbox.trySend(SessionMsg.LinkDown)
    }

    fun cancel() {
        mailbox.trySend(SessionMsg.Cancel)
    }

    private suspend fun run(initialTick: Boolean) {
        try {
            if (initialTick) guarded("recovery tick") { tick() }
            while (!ended) {
                val message = withTimeoutOrNull(tickEvery()) { mailbox.receive() } ?: SessionMsg.Tick
                guarded("message") { onMessage(message) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "File transfer session failed",
                throwable = e,
                fields = mapOf("transferId" to transfer.transferId),
            )
        } finally {
            mailbox.close()
            if (!ended) source.close()
        }
    }

    private suspend fun onMessage(message: SessionMsg) {
        when (message) {
            SessionMsg.Tick -> tick()
            is SessionMsg.Ack -> onAck(message.from, message.highestContiguousIndex)
            is SessionMsg.Cancelled -> onCancelled(message.from, message.reason)
            is SessionMsg.Outcome -> onOutcome(message.outcome)
            SessionMsg.LinkDown -> onLinkDown()
            SessionMsg.Cancel -> cancelOwn()
        }
    }

    /** The per-transfer tick: a third of the file sweep cadence. */
    private fun tickEvery(): Duration = ctx.routerConfig.value.fileSweepInterval / 3

    /**
     * Expiry, a lost OFFER re-armed, a stalled window refilled, and the gone signals: before the
     * OFFER lands, no link or no ACK within `relayBackupAfter` (the message triggers); after, no
     * link or no ACK at all for `chunkAckTimeout` while chunks are in flight — never one slow chunk.
     */
    private suspend fun tick() {
        val now = ctx.clock.now()
        val config = ctx.routerConfig.value
        if (now >= transfer.expiresAt) return end(FileTransferStatus.EXPIRED)
        val linked = ctx.peerLinks.linksOf(transfer.peer).isNotEmpty()
        when (transfer.status) {
            FileTransferStatus.OFFERED -> {
                val queuedOffer = transfer.offerPacketId?.let { it in outboxStore.packetIdsByTag(tag()) } == true
                if (!queuedOffer) sendOffer()
                val sentAt = offerSentAt
                if (!linked || (sentAt != null && now - sentAt > config.relayBackupAfter)) reportGone()
            }

            else -> {
                settle()
                if (ended) return
                val silent = now - lastProgressAt > config.chunkAckTimeout
                if (queued.isNotEmpty() && (!linked || silent)) reportGone()
            }
        }
    }

    private suspend fun onAck(from: PeerId, highestContiguousIndex: Int) {
        if (from != transfer.peer) return
        val covered = minOf(highestContiguousIndex, transfer.shape.chunkCount - 1)
        if (covered <= transfer.contiguousAcked) return
        lastProgressAt = ctx.clock.now()
        transfer = transfer.copy(
            contiguousAcked = covered,
            nextChunkIndex = maxOf(transfer.nextChunkIndex, covered + 1),
        )
        transferStore.setProgress(transfer.transferId, transfer.nextChunkIndex, transfer.contiguousAcked)
        emitEvent(TransferEvent.Progress(transfer))
        settle()
    }

    private suspend fun onCancelled(from: PeerId, reason: FileCancelReason) {
        if (from != transfer.peer) return
        val status = when (reason) {
            FileCancelReason.DECLINED, FileCancelReason.NO_SPACE -> FileTransferStatus.DECLINED
            FileCancelReason.ABORT -> FileTransferStatus.ENDED
        }
        end(status)
    }

    private suspend fun onOutcome(outcome: OutboxOutcome) {
        val isOffer = outcome.packetId == transfer.offerPacketId
        when (outcome) {
            is OutboxOutcome.Acked -> {
                lastProgressAt = ctx.clock.now()
                if (isOffer) {
                    if (transfer.status == FileTransferStatus.OFFERED) {
                        transferStore.setStatus(transfer.transferId, FileTransferStatus.STREAMING)
                    }
                    transfer = transfer.copy(status = FileTransferStatus.STREAMING)
                    settle()
                } else {
                    forgetChunk(outcome.packetId)
                    settle()
                }
            }

            // DECLINED on the OFFER is the peer's refusal; on a chunk, or any other terminal
            // NACK, the transfer is gone at that end and ends quietly.
            is OutboxOutcome.Nacked -> end(
                if (isOffer && outcome.reason == PacketNackReason.DECLINED) FileTransferStatus.DECLINED
                else FileTransferStatus.ENDED,
            )

            is OutboxOutcome.Expired -> when {
                ctx.clock.now() >= transfer.expiresAt -> end(FileTransferStatus.EXPIRED)
                isOffer -> sendOffer()
                else -> {
                    val index = forgetChunk(outcome.packetId) ?: return
                    transfer = transfer.copy(nextChunkIndex = minOf(transfer.nextChunkIndex, index))
                    transferStore.setProgress(transfer.transferId, transfer.nextChunkIndex, transfer.contiguousAcked)
                    settle()
                }
            }
        }
    }

    private suspend fun onLinkDown() {
        if (transfer.status == FileTransferStatus.OFFERED || queued.isNotEmpty()) reportGone()
    }

    /** Refills the window and ends the transfer once the peer holds every chunk. */
    private suspend fun settle() {
        if (transfer.status != FileTransferStatus.STREAMING) return
        val count = transfer.shape.chunkCount
        if (transfer.contiguousAcked >= count - 1) return end(FileTransferStatus.DONE)
        var next = maxOf(transfer.nextChunkIndex, transfer.contiguousAcked + 1)
        val window = ctx.routerConfig.value.maxInFlightChunks
        while (queued.size < window && next < count) {
            if (next in queued) {
                next += 1
                continue
            }
            val ciphertext = source.chunk(next) ?: run {
                transfer = transfer.copy(nextChunkIndex = next)
                return end(FileTransferStatus.ENDED)
            }
            val envelope = packets.chunk(transfer, next, ciphertext)
            outbox.enqueueAndWake(envelope, ctx.clock.now(), tag = tag(), retryClass = RetryClass.FLAT)
            transferStore.recordChunk(transfer.transferId, next, envelope.packetId)
            queued += next
            next += 1
        }
        if (next != transfer.nextChunkIndex) {
            transferStore.setProgress(transfer.transferId, next, transfer.contiguousAcked)
            transfer = transfer.copy(nextChunkIndex = next)
        }
        // Every chunk queued and every queued chunk ACKed: the peer holds them all.
        if (next >= count && queued.isEmpty()) end(FileTransferStatus.DONE)
    }

    private suspend fun sendOffer() {
        val envelope = packets.offer(transfer, source.offer)
        outbox.enqueueAndWake(envelope, ctx.clock.now(), tag = tag(), retryClass = RetryClass.STANDARD)
        transferStore.setOffer(transfer.transferId, envelope.packetId)
        offerSentAt = ctx.clock.now()
        transfer = transfer.copy(offerPacketId = envelope.packetId)
    }

    private suspend fun cancelOwn() {
        val cancel = FileHeader.Cancel(transfer.transferId, FileCancelReason.ABORT)
        outbox.enqueueAndWake(packets.control(transfer.peer, cancel, transfer.expiresAt), ctx.clock.now())
        end(FileTransferStatus.CANCELLED)
    }

    /** Drops a chunk row's mapping; returns its index, or null when it was not one of ours. */
    private suspend fun forgetChunk(packetId: Uuid): Int? {
        val (owner, index) = transferStore.chunkByPacket(packetId) ?: return null
        if (owner != transfer.transferId) return null
        queued -= index
        transferStore.deleteChunk(packetId)
        return index
    }

    private suspend fun reportGone() {
        if (goneReported) return
        goneReported = true
        emitEvent(TransferEvent.Gone(transfer))
    }

    private suspend fun end(status: FileTransferStatus) {
        if (ended) return
        transferStore.setStatus(transfer.transferId, status)
        outboxStore.deleteByTag(tag())
        transferStore.deleteChunks(transfer.transferId)
        source.close()
        transfer = transfer.copy(status = status)
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_TRANSFER_ENDED,
            message = "File transfer ended",
            fields = mapOf("transferId" to transfer.transferId, "peer" to transfer.peer, "status" to status),
        )
        ended = true
        onEnded(transfer.transferId)
        emitEvent(TransferEvent.Ended(transfer))
    }

    private fun tag() = OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transfer.transferId.value)

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
                fields = mapOf("transferId" to transfer.transferId),
            )
        }
    }
}
