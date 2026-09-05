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
import org.yapyap.routing.router.BootstrapIntroEvent
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Handles an out-of-band bootstrap intro ([org.yapyap.protocol.packet.PacketType.BOOTSTRAP]).
 *
 * The packet is authenticated by the preshared-key AEAD gate ([org.yapyap.protection.service.EnvelopeProtectionService.openBootstrap])
 * — not by a DB-backed author signature, which is the whole point (no sponsor row exists yet).
 * On success the intro is forwarded to [bootstrapIntros] for the orchestrator to persist the
 * sponsor's provisional rows and trigger the global-room range sync; the ACK that clears the
 * sponsor's outbox is sent by the inbound processor when this handler returns [InboundHandleResult.Success].
 */
internal class BootstrapInboundHandler(
    private val ctx: RoutingContext,
    private val bootstrapIntros: MutableSharedFlow<BootstrapIntroEvent>,
) : InboundEnvelopeHandler {

    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
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
            // The envelope header source is AEAD-bound, so a mismatch with the attested device id
            // means the intro was assembled inconsistently — reject.
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = "Bootstrap envelope source does not match attested intro device id",
                fields = mapOf(
                    "sourceDeviceId" to bootstrapEnvelope.source,
                    "attestedDeviceId" to payload.device.deviceId,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.PROTECTION_FAILED)
        }

        // TODO(sprint 4 onboarding): cross-check payload.torEndpoint against the transport-proven
        // Tor source onion once handleTorInbound plumbed the connection source through to handlers.
        // TODO(sprint 4 onboarding): the sponsor's provisional rows are inserted by the orchestrator
        // after this event; ordering means the ACK (on Success) may precede persistence — revisit
        // once the insert lands so a failed persist can NACK instead of ACK.

        bootstrapIntros.emit(BootstrapIntroEvent(payload, receivedAt = ctx.clock.now()))
        return InboundHandleResult.Success()
    }
}