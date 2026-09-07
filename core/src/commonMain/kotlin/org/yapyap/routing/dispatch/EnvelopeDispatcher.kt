package org.yapyap.routing.dispatch

import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext

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
    ) {
        when (transport) {
            RouterTransport.TOR -> ctx.torTransport.send(
                endpointOverride ?: ctx.identityResolver.resolveTorEndpointForDevice(envelope.target),
                envelope,
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