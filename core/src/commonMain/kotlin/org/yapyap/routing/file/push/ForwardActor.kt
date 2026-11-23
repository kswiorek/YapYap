package org.yapyap.routing.file.push

import kotlinx.coroutines.channels.Channel
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

internal sealed interface ForwardInput {
    data class Outcome(val outcome: OutboxOutcome) : ForwardInput
    data object Complete : ForwardInput
    data object Cancelled : ForwardInput
    data object Tick : ForwardInput
}

/**
 * One forward per WHOLE relay transfer, on the push engine (design §7.2.4): the same pusher
 * pattern over a DB chunk source. Replays the stored OFFER payload and chunk blobs verbatim
 * (the author's inner signature stays intact), enqueued with tag `FILE_FORWARD`. Never runs
 * the deposit policy and never deposits elsewhere (no chaining). On COMPLETE/terminal the
 * relay transfer is deleted (forget after delivery). Rebuilt on restart from
 * `file_relay_transfers` rows in FORWARDING/STREAMING.
 */
internal class ForwardActor(
    private val ctx: RoutingContext,
    private val relay: FileRelayStore,
    private val outbox: OutboxProcessor,
    private val outboxStore: PacketOutbox,
    private val transferId: TransferId,
    private val onTerminal: suspend (TransferId) -> Unit,
) {
    val mailbox = Channel<ForwardInput>(Channel.UNLIMITED)

    private fun tag() = OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value)

    private var ended = false

    suspend fun recoverAndStart() {
        val row = relay.find(transferId) ?: run {
            ended = true
            return
        }
        if (ctx.clock.now() >= row.expiresAt) {
            drop()
            return
        }
        when (row.status) {
            // A restart must never assume the marker landed: FORWARDING re-sends the marker
            // and waits for its ACK, exactly like a fresh forward. Only STREAMING (marker
            // ACK observed) may stream straight away. Otherwise chunks would overtake their
            // OFFER (§3.5) and the target would DECLINE them all.
            FileRelayStatus.WHOLE,
            FileRelayStatus.FORWARDING,
                -> sendMarker()

            FileRelayStatus.STREAMING -> resumeStream()
            FileRelayStatus.RESERVED -> Unit
        }
    }

    suspend fun run() {
        try {
            for (input in mailbox) {
                handle(input)
                if (ended) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Relay forward failed",
                throwable = e,
                fields = mapOf("transferId" to transferId),
            )
        } finally {
            try {
                onTerminal(transferId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Relay forward terminal cleanup failed",
                    throwable = e,
                    fields = mapOf("transferId" to transferId),
                )
            }
        }
    }

    private suspend fun handle(input: ForwardInput) {
        when (input) {
            is ForwardInput.Outcome -> onOutcome(input.outcome)
            is ForwardInput.Complete -> drop()
            is ForwardInput.Cancelled -> drop()
            is ForwardInput.Tick -> onTick()
        }
    }

    private suspend fun onOutcome(outcome: OutboxOutcome) {
        val row = relay.find(transferId) ?: run {
            ended = true
            return
        }
        when (outcome) {
            is OutboxOutcome.Acked -> {
                if (outcome.packetId == row.markerPacketId &&
                    (row.status == FileRelayStatus.WHOLE || row.status == FileRelayStatus.FORWARDING)
                ) {
                    relay.setStatus(transferId, FileRelayStatus.STREAMING)
                    streamUnqueued()
                    // An empty suffix has no chunk whose ACK would end the forward: it ends here.
                    if (relay.chunkCount(transferId) == 0L) drop()
                } else if (row.status == FileRelayStatus.STREAMING || row.status == FileRelayStatus.FORWARDING) {
                    relay.findChunkByPacket(outcome.packetId)?.let { chunk ->
                        if (chunk.transferId == transferId) {
                            relay.deleteChunkByPacket(outcome.packetId)
                            if (relay.chunkCount(transferId) == 0L) {
                                drop()
                            }
                        }
                    }
                }
            }

            is OutboxOutcome.Nacked,
            is OutboxOutcome.Expired,
                -> {
                // A stale packet (an earlier marker re-sent after a restart, or a chunk row
                // replaced by a restream) says nothing about this forward: ignore it.
                val current = outcome.packetId == row.markerPacketId ||
                        relay.findChunkByPacket(outcome.packetId)?.transferId == transferId
                if (!current) return
                // Target DECLINED the forwarded OFFER (no space) or the transfer is gone
                // there; any other terminal NACK or expiry means this forward never
                // delivers: forgetful end, never re-offered.
                drop()
            }
        }
    }

    private suspend fun onTick() {
        val row = relay.find(transferId) ?: run {
            ended = true
            return
        }
        if (ctx.clock.now() >= row.expiresAt) {
            drop()
            return
        }
        if (row.status == FileRelayStatus.WHOLE) {
            sendMarker()
        } else if (row.status == FileRelayStatus.FORWARDING) {
            // The marker's row left the outbox without an outcome reaching us (outcomes are not
            // replayed): send it again rather than hold the relay's storage until the TTL.
            val marker = row.markerPacketId
            if (marker == null || marker !in outboxStore.packetIdsByTag(tag())) sendMarker()
        } else if (row.status == FileRelayStatus.STREAMING) {
            // Restream rows lost while down: anything stored without a queued outbox row
            // goes again (duplicates merge at the receiver). FORWARDING waits for its
            // marker ACK first — chunks never overtake their OFFER.
            streamUnqueued()
        }
    }

    private suspend fun sendMarker() {
        val row = relay.find(transferId) ?: run {
            ended = true
            return
        }
        // WHOLE (fresh forward) and FORWARDING (restart before the marker ACK) both
        // (re-)send the marker; STREAMING already passed this gate.
        if (row.status != FileRelayStatus.WHOLE && row.status != FileRelayStatus.FORWARDING) return
        val now = ctx.clock.now()
        val packetId = Uuid.random()
        val outer = BinaryEnvelope(
            packetId = packetId,
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = row.expiresAt,
            source = ctx.localDeviceId,
            target = row.targetDevice,
            payload = row.offerPayload,
        )
        outbox.enqueueAndWake(outer, ctx.clock.now(), tag = tag(), retryClass = RetryClass.STANDARD)
        relay.setMarker(transferId, packetId)
        AppLog.debug(
            component = LogComponent.FILE,
            event = LogEvent.FILE_FORWARDED,
            message = "Forwarding whole relay transfer",
            fields = mapOf("transferId" to transferId),
        )
    }

    private suspend fun resumeStream() {
        val row = relay.find(transferId) ?: run {
            ended = true
            return
        }
        if (row.status != FileRelayStatus.STREAMING && row.status != FileRelayStatus.FORWARDING) return
        if (row.status == FileRelayStatus.FORWARDING) {
            relay.setStatus(transferId, FileRelayStatus.STREAMING)
        }
        streamUnqueued()
    }

    /** STREAMING resume (also restart recovery): enqueue chunks with no outbox row. */
    private suspend fun streamUnqueued() {
        val row = relay.find(transferId) ?: run {
            ended = true
            return
        }
        val queued = outboxStore.packetIdsByTag(tag()).toSet()
        // Index-only scan; a blob is loaded only for a chunk that actually goes out.
        for (chunk in relay.chunkRefs(transferId)) {
            if (chunk.packetId != null && chunk.packetId in queued) continue
            val blob = relay.chunkBlob(transferId, chunk.chunkIndex) ?: continue
            val packetId = Uuid.random()
            val outer = BinaryEnvelope(
                packetId = packetId,
                packetType = PacketType.FILE,
                dispositionRequested = true,
                createdAt = ctx.clock.now(),
                expiresAt = row.expiresAt,
                source = ctx.localDeviceId,
                target = row.targetDevice,
                payload = blob,
            )
            outbox.enqueueAndWake(outer, ctx.clock.now(), tag = tag(), retryClass = RetryClass.FLAT)
            relay.setChunkPacket(transferId, chunk.chunkIndex, packetId)
        }
    }

    /**
     * Forget the transfer: relay rows first, then outbox rows (the documented order). A crash
     * in between leaves outbox rows whose outcome no actor claims; the reverse would leave a
     * relay row that restart rebuilds and restreams to a target that already finished.
     */
    private suspend fun drop() {
        relay.delete(transferId)
        outboxStore.deleteByTag(tag())
        ended = true
    }
}
