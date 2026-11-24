package org.yapyap.routing.file.relay

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.MAX_SEAL_BYTES
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.fileControl
import org.yapyap.routing.file.inbound.ChunkSink
import org.yapyap.routing.file.inbound.IncomingHop
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.isKnownNonBanned
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.RelayTransfer

/**
 * The relay's inbound half (docs/envelope-redesign.md §5.4): admission of deposits and parking of
 * their chunks. Dumb to content, strict about its storage contract:
 *
 * - a deposit is admitted only from its origin (the hop's source is the seal's origin: one hop,
 *   ever), for a known final target, with an acceptable shape, within the storage budget —
 *   evicting whole deposits, least recently fed first;
 * - chunks are stored as they arrive and answered with the stored coverage, so the depositor's
 *   window advances; once every chunk is there the deposit is whole and [forwards] takes over.
 */
internal class RelayDoor(
    private val ctx: RoutingContext,
    private val relays: FileRelayStore,
    private val forwards: RelayForwards,
) : ChunkSink {
    suspend fun offer(env: BinaryEnvelope, header: FileHeader.Offer): InboundHandleResult {
        val existing = relays.find(header.transferId)
        if (existing != null) {
            // The depositor re-sends its OFFER until our ACK lands; only it may.
            return if (existing.origin == env.source) InboundHandleResult.Success()
            else InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val admissible = env.source == header.origin &&
                ctx.devices.isKnownNonBanned(header.origin) &&
                ctx.devices.getDeviceStatus(header.finalTarget) != null &&
                header.shape.isAcceptable() &&
                env.content.size in 1..MAX_SEAL_BYTES
        if (!admissible) {
            AppLog.info(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Declined a deposit: not from its origin, for an unknown device, or out of shape",
                fields = mapOf("transferId" to header.transferId, "source" to env.source, "origin" to header.origin),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val now = ctx.clock.now()
        val shape = header.shape
        val transfer = RelayTransfer(
            transferId = header.transferId,
            sealId = ctx.envelopeProtections.hop.contentId(env.content),
            origin = header.origin,
            finalTarget = header.finalTarget,
            shape = shape,
            sealBytes = env.content,
            status = if (shape.chunkCount == 0) FileRelayStatus.WHOLE else FileRelayStatus.RESERVED,
            reservedBytes = env.content.size + shape.byteCount + shape.chunkCount.toLong() * FileProtection.CHUNK_OVERHEAD_BYTES,
            createdAt = now,
            lastChunkAt = now,
            expiresAt = env.expiresAt,
        )
        val admission = relays.admit(transfer, ctx.routerConfig.value.fileRelayBudgetBytes)
        admission.evicted.forEach { forwards.drop(it) }
        if (!admission.admitted) return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        if (transfer.status == FileRelayStatus.WHOLE) forwards.onWhole(transfer.transferId)
        return InboundHandleResult.Success()
    }

    override suspend fun find(transferId: TransferId): IncomingHop? =
        relays.find(transferId)?.let { IncomingHop(it.transferId, it.origin, it.shape) }

    override suspend fun accept(hop: IncomingHop, index: Int, ciphertext: ByteArray): InboundHandleResult {
        val status = relays.storeChunk(hop.transferId, index, ciphertext, ctx.clock.now())
            ?: return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        if (status == FileRelayStatus.WHOLE) forwards.onWhole(hop.transferId)
        val stored = relays.chunkIndices(hop.transferId).toHashSet()
        var contiguous = -1
        while ((contiguous + 1) in stored) contiguous += 1
        val relay = relays.find(hop.transferId) ?: return InboundHandleResult.Success()
        val ack = ctx.fileControl(hop.sender, FileHeader.Ack(hop.transferId, contiguous, emptyList()), relay.expiresAt)
        return InboundHandleResult.Success(sideEffects = listOf(InboundSideEffect.EnqueueDirect(ack)))
    }

    /** The depositor ended its deposit: forget it. False when [transferId] is no deposit here. */
    suspend fun cancel(transferId: TransferId, from: PeerId): Boolean {
        val relay = relays.find(transferId) ?: return false
        if (relay.origin == from) forwards.drop(transferId)
        return true
    }
}
