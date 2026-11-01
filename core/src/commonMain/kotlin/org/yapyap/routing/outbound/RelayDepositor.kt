package org.yapyap.routing.outbound

import org.yapyap.protocol.PacketType
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.RoutingContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Store-and-forward copies of a message sent straight to its recipient: the same protected
 * message, re-addressed to each relay [RelaySelectionPolicy] picks for the recipient. Relays
 * hold a copy and forward it once the recipient surfaces, so delivery doesn't need the sender
 * and the recipient online at the same time. When copies are made is the outbox's call
 * ([OutboxProcessor.backUpToRelays]).
 */
internal class RelayDepositor(
    private val ctx: RoutingContext,
    private val relaySelectionPolicy: RelaySelectionPolicy,
    private val enqueue: suspend (BinaryEnvelope, Instant) -> Unit,
) {
    /** Enqueues one copy of [direct] per selected relay; returns how many. */
    suspend fun deposit(direct: BinaryEnvelope): Int {
        val relays = relaySelectionPolicy.selectRelays(direct.target)
        if (relays.isEmpty()) return 0
        val now = ctx.clock.now()
        val lifetime = ctx.routerConfig.value.binaryEnvelopeLifetime
        relays.forEach { relay ->
            val relayEnvelope = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.MESSAGE,
                dispositionRequested = true,
                createdAt = now,
                expiresAt = now + lifetime,
                source = ctx.localDeviceId,
                target = relay,
                payload = direct.payload,
            )
            enqueue(relayEnvelope, now)
        }
        return relays.size
    }
}
