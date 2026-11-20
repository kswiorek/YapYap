package org.yapyap.routing.file.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.file.FileOutbox
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RetryClass
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Relay-side forward driver (design §7.2–§7.4, step 18): once a deposit is WHOLE,
 * replays the stored OFFER to the ultimate target as a normal outbox marker and,
 * on its ACK, streams the stored chunk envelopes. Original sender signatures ride
 * verbatim — nothing is re-signed or re-encrypted here, and the relay holds no key.
 *
 * The same transfer protocol as the authoring pusher, over a different chunk source
 * (stored ciphertext instead of freshly encrypted plaintext): one marker, per-chunk
 * rows, per-chunk target ACKs draining rows, the transfer row dropped when drained.
 * Never deposits elsewhere (no chaining, invariant 2) and never touches the
 * orchestrator — routing-only, no account events.
 */
internal class FileRelayForwarder(
    private val ctx: RoutingContext,
    private val relay: FileRelayStore,
    private val outbox: FileOutbox,
) {
    private val wakeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Nudges the sweep loop (e.g. after the door stores a chunk). */
    fun wake() {
        wakeSignal.tryEmit(Unit)
    }

    fun runIn(scope: CoroutineScope): Job = scope.launch {
        runCatching { sweep() }
        while (true) {
            withTimeoutOrNull(30.seconds) { wakeSignal.first() }
            runCatching { sweep() }
        }
    }

    internal suspend fun sweep() {
        val now = ctx.clock.now()
        val ttl = ctx.routerConfig.value.fileTransferTtl
        // Forget after delivery — and after expiry: prune dead reservations (their
        // outbox rows go too) so quota frees even when nobody completes them.
        for (dead in relay.expired(now, now - ttl)) {
            runCatching { outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_FORWARD, dead.transferId.value)) }
            relay.delete(dead.transferId)
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_PROCESSED,
                message = "Dropped expired relay transfer",
                fields = mapOf("transferId" to dead.transferId),
            )
        }
        for (row in relay.live()) {
            // Backstop for the door's fast path (concurrent chunk stores can both
            // read a pre-final count): promote once the suffix is provably complete.
            if (row.status == FileRelayStatus.RESERVED && isWhole(row.transferId)) {
                relay.setStatus(row.transferId, FileRelayStatus.WHOLE, now)
            }
        }
        for (row in relay.live()) {
            when (row.status) {
                FileRelayStatus.WHOLE -> runCatching { startForward(row.transferId) }
                FileRelayStatus.STREAMING -> runCatching { resumeStream(row.transferId) }
                else -> Unit
            }
        }
    }

    /** Target ACK / terminal NACK / expiry of a forwarded marker or chunk row. */
    suspend fun onOutboxOutcome(outcome: OutboxOutcome) {
        if (outcome.tag.kind != OutboxTag.Kind.FILE_FORWARD) return
        val transferId = TransferId(outcome.tag.id)
        val row = relay.find(transferId) ?: return // authoring side owns it (pusher)
        when (outcome) {
            is OutboxOutcome.Acked -> {
                if (outcome.packetId == row.markerPacketId && row.status == FileRelayStatus.FORWARDING) {
                    streamChunks(transferId)
                } else if (row.status == FileRelayStatus.STREAMING) {
                    relay.findChunkByPacket(outcome.packetId)?.let { chunk ->
                        if (chunk.transferId == transferId) {
                            relay.deleteChunkByPacket(outcome.packetId)
                            if (relay.chunkCount(transferId) == 0L) {
                                runCatching {
                                    outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value))
                                }
                                relay.delete(transferId)
                            }
                        }
                    }
                }
            }

            // Target DECLINED the forwarded OFFER (the only reachable refusal: no
            // space) or the transfer is gone there — drop it and free the quota
            // (re-offering would be spam, §7.2). Any other terminal NACK or an
            // expiry means this forward will never deliver: same forgetful end.
            is OutboxOutcome.Nacked,
            is OutboxOutcome.Expired,
                -> {
                runCatching { outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value)) }
                relay.delete(transferId)
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_PROCESSED,
                    message = "Dropped relay transfer on terminal outcome",
                    fields = mapOf("transferId" to transferId),
                )
            }
        }
    }

    // -- Internals ---------------------------------------------------------------

    private suspend fun isWhole(transferId: TransferId): Boolean {
        val row = relay.find(transferId) ?: return false
        val fileCount = fileCount(row.totalBytes, row.chunkSizeBytes)
        val expected = maxOf(0, fileCount - row.startIndex)
        return relay.chunkCount(transferId) >= expected
    }

    /** WHOLE → FORWARDING: replay the stored OFFER as a normal outbox marker. */
    private suspend fun startForward(transferId: TransferId) {
        val row = relay.find(transferId) ?: return
        if (row.status != FileRelayStatus.WHOLE) return
        val now = ctx.clock.now()
        if (now >= row.expiresAt) {
            runCatching { outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value)) }
            relay.delete(transferId)
            return
        }
        val packetId = Uuid.random()
        val outer = BinaryEnvelope(
            packetId = packetId,
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = row.expiresAt,
            // Outer source is the relay (message-transit pattern §7.4); the inner
            // source stays the original sender, signature intact.
            source = ctx.localDeviceId,
            target = row.targetDevice,
            payload = row.offerPayload,
        )
        try {
            outbox.enqueue(outer, OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        relay.setMarker(transferId, packetId)
    }

    /** Marker ACKed: stream every stored chunk (bounded: ≤10 MB per transfer). */
    private suspend fun streamChunks(transferId: TransferId) {
        val row = relay.find(transferId) ?: return
        if (row.status != FileRelayStatus.FORWARDING) return
        relay.setStatus(transferId, FileRelayStatus.STREAMING, ctx.clock.now())
        resumeStream(transferId)
    }

    /** STREAMING resume (also restart recovery): enqueue chunks with no outbox row. */
    private suspend fun resumeStream(transferId: TransferId) {
        val row = relay.find(transferId) ?: return
        if (row.status != FileRelayStatus.STREAMING) return
        val now = ctx.clock.now()
        if (now >= row.expiresAt) {
            runCatching { outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value)) }
            relay.delete(transferId)
            return
        }
        for (chunk in relay.chunks(transferId)) {
            val queued = chunk.packetId?.let { packet ->
                try {
                    outbox.isPacketQueued(packet)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    true // unknown: leave it; the next sweep re-checks
                }
            } ?: false
            if (queued) continue
            val packetId = Uuid.random()
            val outer = BinaryEnvelope(
                packetId = packetId,
                packetType = PacketType.FILE,
                dispositionRequested = true,
                createdAt = now,
                expiresAt = row.expiresAt,
                source = ctx.localDeviceId,
                target = row.targetDevice,
                payload = chunk.blob,
            )
            try {
                outbox.enqueue(
                    outer,
                    OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value),
                    retryClass = RetryClass.FLAT
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            relay.setChunkPacket(transferId, chunk.chunkIndex, packetId)
        }
    }

    private fun fileCount(totalBytes: Long, chunkSizeBytes: Int): Int =
        if (totalBytes == 0L) 0
        else ((totalBytes - 1) / chunkSizeBytes + 1).toInt()
}
