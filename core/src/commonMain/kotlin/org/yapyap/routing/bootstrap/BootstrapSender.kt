package org.yapyap.routing.bootstrap

import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.OutboxTag

/**
 * Sends a bootstrap-family packet: protect inside routing (plaintext in, ciphertext in the
 * outbox, scheme chosen by kind), queue with `dispositionRequested` and the short
 * envelope TTL [bootstrapIntroLifetime][org.yapyap.routing.router.RouterConfig.bootstrapIntroLifetime]
 * (distinct from the newcomer's session budget
 * the orchestrator's onboarding session budget).
 *
 * `targetEndpoint` out-of-band endpoint override for targets with no local devices row
 *   (persisted on the outbox row, preferred at dispatch).
 * `sharedSecret` sender's in-memory one-time secret, required for INTRO; never persisted
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
        val protected = ctx.envelopeProtections.bootstrap.protect(
            payload,
            source = ctx.localDeviceId,
            target = target,
            sharedSecret = sharedSecret,
        )
        val now = ctx.clock.now()
        val envelope = ctx.envelope(
            packetType = PacketType.BOOTSTRAP,
            target = target,
            routingHeader = protected.header.encode(),
            content = protected.content,
            expiresAt = now + ctx.routerConfig.value.bootstrapIntroLifetime,
            dispositionRequested = true,
        )
        outboxProcessor.enqueueAndWake(
            envelope,
            nextRetryAt = now,
            relayMessage = false,
            targetEndpoint = targetEndpoint,
            tag = OutboxTag.Bootstrap,
        )
    }
}