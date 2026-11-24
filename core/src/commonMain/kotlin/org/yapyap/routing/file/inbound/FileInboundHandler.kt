package org.yapyap.routing.file.inbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.relay.RelayDoor
import org.yapyap.routing.file.seal.SealRegistry
import org.yapyap.routing.file.transfer.TransferEngine
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext

/**
 * The FILE family's inbound face (docs/envelope-redesign.md §5): dispatch by routing header.
 *
 * - OFFER opens a transfer to us: a seal for us goes to the [target], one for another device to
 *   the relay [door].
 * - CHUNK goes to the sink that receives its transfer, after the checks every chunk passes: from
 *   the transfer's sender, inside its shape, exactly the length its index allows. A chunk for no
 *   transfer of ours is answered DECLINED (chunks never overtake their OFFER, so it is gone).
 * - ACK and CANCEL answer a transfer: one we send ([engine]), or for CANCEL one we receive.
 * - COMPLETE tells a seal's origin ([seals]) that the target holds the file.
 * - REQUEST asks us to serve ([serve]).
 */
internal class FileInboundHandler(
    private val ctx: RoutingContext,
    private val target: TargetSide,
    private val door: RelayDoor,
    private val engine: TransferEngine,
    private val seals: SealRegistry,
    private val serve: FileServeHandler,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val header = runCatching { FileHeader.decode(env.routingHeader) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode file header",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }
        return when (header) {
            is FileHeader.Offer ->
                if (header.finalTarget == ctx.localDeviceId) target.offer(env, header) else door.offer(env, header)

            is FileHeader.Chunk -> chunk(env, header)
            is FileHeader.Ack -> {
                engine.onAck(header.transferId, env.source, header.highestContiguousIndex)
                InboundHandleResult.Success()
            }

            is FileHeader.Cancel -> {
                // A transfer we send, one we receive as its target, or a deposit we hold.
                if (engine.owns(header.transferId)) {
                    engine.onCancelled(header.transferId, env.source, header.reason)
                } else if (!target.cancel(header.transferId, env.source)) {
                    door.cancel(header.transferId, env.source)
                }
                InboundHandleResult.Success()
            }

            is FileHeader.Complete -> {
                seals.onComplete(header.sealId, env.source)
                InboundHandleResult.Success()
            }

            FileHeader.Request -> serve.handle(env)
        }
    }

    private suspend fun chunk(env: BinaryEnvelope, header: FileHeader.Chunk): InboundHandleResult {
        val (sink, hop) = target.find(header.transferId)?.let { target to it }
            ?: door.find(header.transferId)?.let { door to it }
            ?: return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        val index = header.chunkIndex
        val expectedLength = hop.shape.chunkLength(index) + FileProtection.CHUNK_OVERHEAD_BYTES
        if (env.source != hop.sender || index >= hop.shape.chunkCount || env.content.size != expectedLength) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        return sink.accept(hop, index, env.content)
    }
}
