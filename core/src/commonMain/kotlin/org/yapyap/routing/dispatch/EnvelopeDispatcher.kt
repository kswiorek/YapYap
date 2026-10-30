package org.yapyap.routing.dispatch

import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
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
        /** Cap on the Tor send (null: the transport's own budget); see `RouterConfig.offlinePeerSendBudget`. */
        torBudget: Duration? = null,
    ) {
        when (transport) {
            RouterTransport.TOR -> ctx.torTransport.send(
                endpointOverride ?: ctx.identityResolver.resolveTorEndpointForDevice(envelope.target),
                envelope,
                torBudget,
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