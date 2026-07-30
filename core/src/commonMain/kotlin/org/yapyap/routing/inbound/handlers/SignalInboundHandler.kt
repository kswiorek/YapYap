package org.yapyap.routing.inbound.handlers

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

internal class SignalInboundHandler(
    private val ctx: RoutingContext,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val signalEnvelope = runCatching { WebRtcSignalEnvelope.decode(env.payload) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode signal envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }
        if (signalEnvelope.target != ctx.localDeviceId) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Signal envelope ignored due to target mismatch",
                fields = mapOf(
                    "sourceDeviceId" to signalEnvelope.source,
                    "targetDeviceId" to signalEnvelope.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.WRONG_TARGET)
        }
        val signal = try {
            ctx.envelopeProtectionService.openSignal(signalEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open signal envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        ctx.webRtcTransport.handleBootstrapSignal(signal)
        return InboundHandleResult.Success
    }
}
