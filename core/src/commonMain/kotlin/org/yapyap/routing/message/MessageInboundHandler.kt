package org.yapyap.routing.message

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.MessageHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.isKnownNonBanned
import kotlin.coroutines.cancellation.CancellationException

/**
 * The MESSAGE family's inbound face: relay transit for packets whose final target is another
 * device, and locally the content dispatch — a [DagPayload] node goes to the orchestrator's
 * pipeline, a pull-path [SyncPayload.Request] to [MessageInbound], whose serve-or-decline answer
 * is the packet's disposition. The type split is the guard: the DAG flow cannot carry a request.
 */
internal class MessageInboundHandler(
    private val ctx: RoutingContext,
    private val incomingMessages: MutableSharedFlow<DagPayload>,
    private val inbound: MessageInbound,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val header = runCatching { MessageHeader.decode(env.routingHeader) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message header",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }
        if (header.finalTarget != ctx.localDeviceId) return relay(env, header)

        val content = try {
            ctx.envelopeProtections.message.open(env.content, header.origin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open message content",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }

        return when (content) {
            is DagPayload -> {
                incomingMessages.emit(content)
                InboundHandleResult.Success()
            }

            is SyncPayload -> when (content) {
                is SyncPayload.Request -> inbound.onSyncRequested(content, header.origin)
            }
        }
    }

    /**
     * Store-and-forward for another device (docs/envelope-redesign.md §3.2): admitted only from
     * its origin (one hop, ever — relays never accept from relays), for a known final target. The
     * forward is our own envelope with the same header and content and the same expiry.
     */
    private suspend fun relay(env: BinaryEnvelope, header: MessageHeader): InboundHandleResult {
        if (env.source != header.origin ||
            !ctx.devices.isKnownNonBanned(header.origin) ||
            ctx.devices.getDeviceStatus(header.finalTarget) == null
        ) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Declined relay transit: not from its origin, or for an unknown device",
                fields = mapOf(
                    "sourceDeviceId" to env.source,
                    "originDeviceId" to header.origin,
                    "finalTargetDeviceId" to header.finalTarget,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val forward = ctx.envelope(
            packetType = PacketType.MESSAGE,
            target = header.finalTarget,
            routingHeader = env.routingHeader,
            content = env.content,
            expiresAt = env.expiresAt,
            dispositionRequested = true,
        )
        return InboundHandleResult.Success(sideEffects = listOf(InboundSideEffect.EnqueueForRelay(forward)))
    }
}
