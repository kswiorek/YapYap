package org.yapyap.routing.inbound.handlers

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.BootstrapPacketEvent
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Handles bootstrap-family packets ([org.yapyap.protocol.packet.PacketType.BOOTSTRAP]).
 *
 * The envelope is authenticated by kind ([org.yapyap.protection.service.EnvelopeProtectionService.openBootstrap]):
 * INTRO by the preshared-key AEAD gate — not a DB-backed author signature, since no sponsor row
 * exists yet; RECOVERY_REQUEST by the account-key signature over the device binding. On success the
 * authenticated payload is forwarded to [bootstrapPackets] for the orchestrator to dispatch by kind
 * (newcomer onboarding provider vs recovery responder); the ACK that clears the sender's outbox is
 * sent by the inbound processor when this handler returns [InboundHandleResult.Success].
 */
internal class BootstrapInboundHandler(
    private val ctx: RoutingContext,
    private val bootstrapPackets: MutableSharedFlow<BootstrapPacketEvent>,
) : InboundEnvelopeHandler {

    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val received = ctx.clock.now()
        val bootstrapEnvelope = runCatching { BootstrapEnvelope.decode(env.payload) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode bootstrap envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }

        if (bootstrapEnvelope.target != ctx.localDeviceId) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Bootstrap envelope received for peer ${bootstrapEnvelope.target}",
                fields = mapOf(
                    "sourceDeviceId" to bootstrapEnvelope.source,
                    "targetDeviceId" to bootstrapEnvelope.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.WRONG_TARGET)
        }

        val payload = try {
            ctx.envelopeProtectionService.openBootstrap(bootstrapEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open bootstrap envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }

        if (bootstrapEnvelope.source != payload.device.deviceId) {
            // The envelope header source is AAD-bound, so a mismatch with the attested device id
            // means the packet was assembled inconsistently — reject.
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = "Bootstrap envelope source does not match attested device id",
                fields = mapOf(
                    "sourceDeviceId" to bootstrapEnvelope.source,
                    "attestedDeviceId" to payload.device.deviceId,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.PROTECTION_FAILED)
        }

        // TODO(sprint 4 onboarding): the orchestrator acts after this event; ordering means the ACK
        // (on Success) may precede the responder's work — revisit once the sink-callback seam lands
        // so a failed persist/reply can NACK instead of ACK.

        bootstrapPackets.emit(BootstrapPacketEvent(payload, receivedAt = received))
        return InboundHandleResult.Success()
    }
}