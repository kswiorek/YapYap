package org.yapyap.routing.outbound

import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.router.RoutingContext
import kotlin.uuid.Uuid

/**
 * Sends a bootstrap-family packet to a peer.
 *
 * Protection happens here, inside routing (plaintext in, ciphertext in the outbox), mirroring
 * [OutboundMessenger] / [SystemSender]: the protection scheme is chosen by kind inside
 * [org.yapyap.protection.envelope.BootstrapProtection] (SECRET_AEAD for an INTRO, ACCOUNT_SIGNED for
 * a RECOVERY_REQUEST). The envelope is queued through the outbox with `dispositionRequested = true`
 * so the peer's ACK clears it, and a deliberately short lifetime
 * ([org.yapyap.routing.router.RouterConfig.bootstrapIntroLifetime]) — a stale intro must not linger.
 */
internal class BootstrapSender(
    private val ctx: RoutingContext,
    private val outboxProcessor: OutboxProcessor,
) {
    suspend fun sendBootstrap(payload: BootstrapPayload, target: PeerId) {
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