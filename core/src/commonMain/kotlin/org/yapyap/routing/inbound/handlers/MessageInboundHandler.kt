package org.yapyap.routing.inbound.handlers

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LoggingTypes
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

internal class MessageInboundHandler(
    private val ctx: RoutingContext,
    private val incomingMessages: MutableSharedFlow<MessagePayload>,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val messageEnvelope = runCatching { MessageEnvelope.decode(env.payload) }.getOrNull() ?: run {
            ctx.logger.warn(
                component = LogComponent.ROUTER,
                event = LoggingTypes.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }

        if (messageEnvelope.target != ctx.localDeviceId) {
            ctx.logger.info(
                component = LogComponent.ROUTER,
                event = LoggingTypes.ENVELOPE_WRONG_TARGET,
                message = "Message envelope received for peer ${messageEnvelope.target}",
                fields = mapOf(
                    "sourceDeviceId" to messageEnvelope.source,
                    "targetDeviceId" to messageEnvelope.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            // TODO relay logic
            return InboundHandleResult.Success
        }

        val payload = try {
            ctx.envelopeProtectionService.openMessage(messageEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            ctx.logInboundProtectionFailure(
                message = "Failed to open message envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        incomingMessages.emit(payload)
        return InboundHandleResult.Success
    }
}
