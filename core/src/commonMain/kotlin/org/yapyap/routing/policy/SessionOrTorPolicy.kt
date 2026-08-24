package org.yapyap.routing.policy

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RouterTransport

class SessionOrTorPolicy(
    private val config: StateFlow<RouterConfig>,
) : OutboundPolicy {
    override fun resolve(target: PeerId, hasWebRtcSession: Boolean, retries: Long, forced: RouterTransport?): ResolvedOutbound {
        val configSnapshot = config.value
        var transport: RouterTransport
        if (forced != null) {
            transport = forced
        }
        else if (hasWebRtcSession) {
            transport = RouterTransport.WEBRTC
        } else {
            transport = RouterTransport.TOR
        }

        var retryDelay = configSnapshot.standbyRetryDelaySeconds

        if (retries <= configSnapshot.messageMaxRetries) {
            retryDelay = configSnapshot.getRetryDelaySeconds(transport)
        }

        return ResolvedOutbound(transport, retryDelay)
    }
}