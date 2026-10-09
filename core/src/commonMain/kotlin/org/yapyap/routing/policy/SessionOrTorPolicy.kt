package org.yapyap.routing.policy

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RouterTransport

class SessionOrTorPolicy(
    private val config: StateFlow<RouterConfig>,
) : OutboundPolicy {
    override fun resolve(
        target: PeerId,
        hasWebRtcSession: Boolean,
        retries: Long,
        forced: RouterTransport?
    ): ResolvedOutbound {
        val configSnapshot = config.value
        val transport: RouterTransport = forced ?: if (hasWebRtcSession) {
            RouterTransport.WEBRTC
        } else {
            RouterTransport.TOR
        }

        var retryDelay = configSnapshot.standbyRetryDelay

        if (retries <= configSnapshot.fastRetryBudget) {
            retryDelay = configSnapshot.getRetryDelay(transport)
        }

        return ResolvedOutbound(transport, retryDelay)
    }
}