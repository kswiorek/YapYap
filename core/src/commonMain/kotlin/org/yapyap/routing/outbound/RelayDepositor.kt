package org.yapyap.routing.outbound

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.RoutingContext

/**
 * Store-and-forward copies of a packet sent straight to its recipient: the same routing header and
 * content in a new envelope to each relay [RelaySelectionPolicy] picks for the recipient, signed by
 * us and expiring with the original (a copy never outlives what it copies). Relays hold a copy and
 * forward it once the recipient surfaces, so delivery doesn't need the sender and the recipient
 * online at the same time. When copies are made is the outbox's call
 * ([OutboxProcessor.backUpToRelays]); only messages ask for them.
 */
//TODO [Router cleanup]: only used in OutboxProcessor, can be private or moved there
internal class RelayDepositor(
    private val ctx: RoutingContext,
    private val relaySelection: RelaySelectionPolicy,
) {
    /** One copy of [direct] per selected relay, ready for the outbox. */
    suspend fun copies(direct: BinaryEnvelope): List<BinaryEnvelope> =
        relaySelection.selectRelays(direct.target).map { relay ->
            ctx.envelope(
                packetType = direct.packetType,
                target = relay,
                routingHeader = direct.routingHeader,
                content = direct.content,
                expiresAt = direct.expiresAt,
                dispositionRequested = true,
            )
        }
}
