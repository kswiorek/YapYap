package org.yapyap.routing.file.relay

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FilePackets
import org.yapyap.routing.file.inbound.TransferSink
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.isKnownNonBanned
import org.yapyap.routing.store.FileIncomingStore
import org.yapyap.routing.store.IncomingTransfer

/**
 * The relay's sink (docs/envelope-redesign.md §5.4): a hop for another device is received like
 * any other, and what it carries is parked as it arrived, since the relay can't open it.
 *
 * - a hop is admitted only from its origin (one hop, ever), for a known final target, within the
 *   storage budget — evicting relay hops, least recently fed first;
 * - chunks are stored as they arrive and answered with the stored coverage, so the depositor's
 *   window advances; once every chunk is there the hop is whole and [forwards] passes it on.
 */
internal class RelayDoor(
    private val ctx: RoutingContext,
    private val packets: FilePackets,
    private val incomingStore: FileIncomingStore,
    private val forwards: RelayForwards,
) : TransferSink {
    override suspend fun offer(env: BinaryEnvelope, header: FileHeader.Offer): InboundHandleResult {
        val admissible = env.source == header.origin &&
                ctx.devices.isKnownNonBanned(header.origin) &&
                ctx.devices.getDeviceStatus(header.finalTarget) != null
        if (!admissible) {
            AppLog.info(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Declined a deposit: not from its origin, or for an unknown device",
                fields = mapOf("transferId" to header.transferId, "source" to env.source, "origin" to header.origin),
            )
            return declined()
        }
        val transfer = IncomingTransfer(
            transferId = header.transferId,
            sealId = ctx.envelopeProtections.hop.contentId(env.content),
            sender = env.source,
            finalTarget = header.finalTarget,
            shape = header.shape,
            expiresAt = env.expiresAt,
        )
        val admission = incomingStore.admitRelay(
            transfer,
            env.content,
            ctx.routerConfig.value.fileRelayBudgetBytes,
            ctx.clock.now(),
        )
        admission.evicted.forEach { forwards.drop(it) }
        if (!admission.admitted) return declined()
        if (header.shape.chunkCount == 0) forwards.onWhole(transfer.transferId)
        return InboundHandleResult.Success()
    }

    override suspend fun accept(transfer: IncomingTransfer, index: Int, ciphertext: ByteArray): InboundHandleResult {
        val coverage = incomingStore.storeRelayChunk(transfer.transferId, index, ciphertext, ctx.clock.now())
            ?: return declined()
        if (coverage.isWhole) forwards.onWhole(transfer.transferId)
        val ack = FileHeader.Ack(transfer.transferId, coverage.highestContiguousIndex, emptyList())
        val reply = packets.control(transfer.sender, ack, transfer.expiresAt)
        return InboundHandleResult.Success(sideEffects = listOf(InboundSideEffect.EnqueueDirect(reply)))
    }

    /** The depositor ended its deposit: forget it. */
    override suspend fun cancel(transfer: IncomingTransfer) {
        forwards.drop(transfer.transferId)
    }

    private fun declined() = InboundHandleResult.Rejected(PacketNackReason.DECLINED)
}
