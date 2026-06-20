package org.yapyap.backend.routing

import org.yapyap.backend.protocol.PeerId

interface OutboundPolicy {
    fun resolve(
        target: PeerId,
        hasWebRtcSession: Boolean,
        retries: Int,
        forced: RouterTransport?=null,      // non-null only in tests / explicit override
    ): ResolvedOutbound
}

data class ResolvedOutbound(
    val transport: RouterTransport,
    val retryDelaySeconds: Long,
)