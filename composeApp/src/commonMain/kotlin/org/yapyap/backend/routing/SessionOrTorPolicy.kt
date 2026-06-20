package org.yapyap.backend.routing

import org.yapyap.backend.protocol.PeerId

class SessionOrTorPolicy(
    private val config: RouterConfig,
) : OutboundPolicy {
    override fun resolve(target: PeerId, hasWebRtcSession: Boolean, retries: Long, forced: RouterTransport?): ResolvedOutbound {

        var transport: RouterTransport
        if (forced != null) {
            transport = forced
        }
        else if (hasWebRtcSession) {
            transport = RouterTransport.WEBRTC
        } else {
            transport = RouterTransport.TOR
        }

        var retryDelay = config.standbyRetryDelaySeconds

        if (retries <= config.messageMaxRetries) {
            retryDelay = config.getRetryDelaySeconds(transport)
        }

        return ResolvedOutbound(transport, retryDelay)
    }
}