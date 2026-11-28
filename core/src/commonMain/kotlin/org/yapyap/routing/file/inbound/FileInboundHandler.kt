package org.yapyap.routing.file.inbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.relay.RelayDoor
import org.yapyap.routing.file.seal.SealRegistry
import org.yapyap.routing.file.transfer.TransferEngine
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingStore
import org.yapyap.routing.store.IncomingTransfer

/**
 * The FILE family's inbound face (docs/envelope-redesign.md §5): dispatch by routing header.
 *
 * Every hop delivering to us is one [IncomingTransfer], received the same way; only what happens
 * to it differs, and that is its [TransferSink]'s, picked by the seal's final target: the
 * [target] when it is us, the [relay] otherwise.
 *
 * - OFFER opens a hop: a re-sent OFFER is answered for its sender alone, and every new one passes
 *   the shared admission before its sink sees it.
 * - CHUNK goes to its hop's sink after the checks every chunk passes: from the hop's sender,
 *   inside its shape, exactly the length its index allows. A chunk for no live hop is answered
 *   DECLINED (chunks never overtake their OFFER, so it is gone).
 * - ACK and CANCEL answer a hop: one we send ([engine]), or for CANCEL one we receive.
 * - COMPLETE tells a seal's origin ([seals]) that the target holds the file.
 * - REQUEST asks us to serve ([serve]).
 */
internal class FileInboundHandler(
    private val ctx: RoutingContext,
    private val incomingStore: FileIncomingStore,
    private val target: TargetSide,
    private val relay: RelayDoor,
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
            is FileHeader.Offer -> offer(env, header)
            is FileHeader.Chunk -> chunk(env, header)
            is FileHeader.Ack -> {
                engine.onAck(header.transferId, env.source, header.highestContiguousIndex)
                InboundHandleResult.Success()
            }

            is FileHeader.Cancel -> {
                if (engine.owns(header.transferId)) {
                    engine.onCancelled(header.transferId, env.source, header.reason)
                } else {
                    incomingStore.findTransfer(header.transferId)
                        ?.takeIf { it.sender == env.source }
                        ?.let { sinkFor(it.finalTarget).cancel(it) }
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

    private suspend fun offer(env: BinaryEnvelope, header: FileHeader.Offer): InboundHandleResult {
        incomingStore.findTransfer(header.transferId)?.let { existing ->
            // The sender re-sends its OFFER until our ACK lands; only it may.
            return if (existing.sender == env.source) InboundHandleResult.Success() else declined()
        }
        // Before any sink, so a refused OFFER never spends a target's ratchet message: we enforce
        // our own small-file limit (a large file is pulled, never pushed) and the seal's size.
        val shape = header.shape
        val admissible = shape.isAcceptable() &&
                shape.byteTo <= ctx.routerConfig.value.smallFileMaxBytes &&
                env.content.size in 1..ctx.fileLimits.value.maxSealBytes
        if (!admissible) return declined()
        return sinkFor(header.finalTarget).offer(env, header)
    }

    private suspend fun chunk(env: BinaryEnvelope, header: FileHeader.Chunk): InboundHandleResult {
        val transfer = incomingStore.findTransfer(header.transferId)
            ?.takeIf { it.expiresAt > ctx.clock.now() }
            ?: return declined()
        val index = header.chunkIndex
        val expectedLength = transfer.shape.chunkLength(index) + FileProtection.CHUNK_OVERHEAD_BYTES
        if (env.source != transfer.sender || index >= transfer.shape.chunkCount || env.content.size != expectedLength) {
            return declined()
        }
        return sinkFor(transfer.finalTarget).accept(transfer, index, env.content)
    }

    private fun sinkFor(finalTarget: PeerId): TransferSink = if (finalTarget == ctx.localDeviceId) target else relay

    private fun declined() = InboundHandleResult.Rejected(PacketNackReason.DECLINED)
}
