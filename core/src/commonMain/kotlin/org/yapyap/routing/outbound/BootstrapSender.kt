package org.yapyap.routing.outbound

import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapIntroPayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.router.RoutingContext
import kotlin.uuid.Uuid

/**
 * Sends the onboarding bootstrap intro to a QR-scanned newcomer.
 *
 * Protection happens here, inside routing (plaintext in, ciphertext in the outbox), mirroring
 * [OutboundMessenger] / [SystemSender]. The envelope is queued through the outbox with
 * `dispositionRequested = true` so the newcomer's ACK clears it, and a deliberately short lifetime
 * ([org.yapyap.routing.router.RouterConfig.bootstrapIntroLifetime]) — a stale intro must not linger.
 */
internal class BootstrapSender(
    private val ctx: RoutingContext,
    private val outboxProcessor: OutboxProcessor,
) {
    suspend fun sendBootstrapIntro(payload: BootstrapIntroPayload) {
        val target = payload.device.deviceId
        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val protected = ctx.envelopeProtectionService.protectBootstrap(payload, context)
        val now = ctx.clock.now()
        val envelope = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.BOOTSTRAP,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + ctx.routerConfig.value.bootstrapIntroLifetime,
            source = ctx.localDeviceId,
            target = target,
            payload = protected.encode(),
        )
        outboxProcessor.enqueueAndWake(envelope, nextRetryAt = now, relayMessage = false)
    }
}