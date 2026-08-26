package org.yapyap.routing.inbound.handlers

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

internal class MessageInboundHandler(
    private val ctx: RoutingContext,
    private val incomingMessages: MutableSharedFlow<MessagePayload>,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val messageEnvelope = runCatching { MessageEnvelope.decode(env.payload) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }

        if (messageEnvelope.target != ctx.localDeviceId) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Message envelope received for peer ${messageEnvelope.target}",
                fields = mapOf(
                    "sourceDeviceId" to messageEnvelope.source,
                    "targetDeviceId" to messageEnvelope.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            val now = ctx.timeProvider.nowEpochSeconds()
            val binaryEnvelope = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.MESSAGE,
                dispositionRequested = true,
                createdAtEpochSeconds = now,
                expiresAtEpochSeconds = now + ctx.routerConfig.value.binaryEnvelopeLifetimeSeconds,
                source = ctx.localDeviceId,
                target = messageEnvelope.target,
                payload = messageEnvelope.encode(),
            )


            return InboundHandleResult.Success(
                sideEffects = listOf(InboundSideEffect.EnqueueForRelay(binaryEnvelope)),
            )
        }

        val payload = try {
            ctx.envelopeProtectionService.openMessage(messageEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open message envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        incomingMessages.emit(payload)
        return InboundHandleResult.Success()
    }
}
