package org.yapyap.routing.outbound

import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.router.RoutingContext
import kotlin.uuid.Uuid

/**
 * Sends a bootstrap-family packet: protect inside routing (plaintext in, ciphertext in the
 * outbox, scheme chosen by kind), queue with `dispositionRequested` and the short
 * [bootstrapIntroLifetime][org.yapyap.routing.router.RouterConfig.bootstrapIntroLifetime].
 *
 * @param targetEndpoint out-of-band endpoint override for targets with no local devices row
 *   (persisted on the outbox row, preferred at dispatch).
 * @param sharedSecret sender's in-memory one-time secret, required for INTRO; never persisted
 *   (used once for protect, the outbox holds the ciphertext). Null for RECOVERY_REQUEST.
 */
internal class BootstrapSender(
    private val ctx: RoutingContext,
    private val outboxProcessor: OutboxProcessor,
) {
    suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint? = null,
        sharedSecret: ByteArray? = null,
    ) {
        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val protected = ctx.envelopeProtectionService.protectBootstrap(payload, context, sharedSecret)
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
        outboxProcessor.enqueueAndWake(
            envelope,
            nextRetryAt = now,
            relayMessage = false,
            targetEndpoint = targetEndpoint
        )
    }
}