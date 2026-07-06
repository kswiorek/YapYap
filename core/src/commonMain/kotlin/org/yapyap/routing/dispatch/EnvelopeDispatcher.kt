package org.yapyap.routing.dispatch

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext

internal class EnvelopeDispatcher(
    private val ctx: RoutingContext,
) {
    suspend fun hasWebRtcSession(peer: PeerId): Boolean =
        ctx.webRtcTransport.getSessionForPeer(peer) != null

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
                val session = ctx.webRtcTransport.getSessionForPeer(envelope.target)
                ctx.webRtcTransport.sendEnvelope(
                    sessionId = session,
                    targetId = envelope.target,
                    envelope = envelope,
                )
            }
        }
    }
}