package org.yapyap.routing.dispatch

import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import org.yapyap.transport.tor.TorLinkId
import kotlin.time.Duration

internal class EnvelopeDispatcher(
    private val ctx: RoutingContext,
) {
    suspend fun dispatch(
        envelope: BinaryEnvelope,
        transport: RouterTransport,
        /**
         * Out-of-band endpoint override: when non-null, the Tor send goes to it verbatim and the
         * devices-table lookup is skipped. Set for bootstrap targets with no local row (recovery
         * request / intro endpoint override from the outbox row) and for ACK/NACKs back to
         * unknown sources (transport-proven inbound endpoint).
         */
        endpointOverride: TorEndpoint? = null,
        /** Cap on a one-shot Tor dial (null: the transport's own budget). */
        torBudget: Duration? = null,
        /** Tor stream to reply on (the one the packet being answered arrived on), when still open. */
        torReplyLink: TorLinkId? = null,
    ) {
        when (transport) {
            RouterTransport.TOR -> ctx.torTransport.send(
                endpointOverride ?: ctx.identityResolver.resolveTorEndpointForDevice(envelope.target),
                envelope,
                torBudget,
                torReplyLink,
            )

            RouterTransport.WEBRTC -> {
                ctx.webRtcTransport.sendEnvelope(
                    targetId = envelope.target,
                    envelope = envelope,
                )
            }
        }
    }
}