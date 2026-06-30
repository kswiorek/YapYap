package org.yapyap.routing.policy

import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.RouterTransport

interface OutboundPolicy {
    fun resolve(
        target: PeerId,
        hasWebRtcSession: Boolean,
        retries: Long,
        forced: RouterTransport?=null,      // non-null only in tests / explicit override
    ): ResolvedOutbound
}

data class ResolvedOutbound(
    val transport: RouterTransport,
    val retryDelaySeconds: Long,
)