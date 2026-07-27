package org.yapyap.routing.dispatch

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext

internal class EnvelopeDispatcher(
    private val ctx: RoutingContext,
) {
   suspend fun dispatch(
        envelope: BinaryEnvelope,
        transport: RouterTransport,
    ) {
        when (transport) {
            RouterTransport.TOR -> ctx.torTransport.send(
                ctx.identityResolver.resolveTorEndpointForDevice(envelope.target),
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