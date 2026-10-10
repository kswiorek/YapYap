package org.yapyap.routing.policy

import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.RouterTransport
import kotlin.time.Duration

internal interface OutboundPolicy {
    fun resolve(
        target: PeerId,
        hasWebRtcSession: Boolean,
        retries: Long,
        forced: RouterTransport? = null,      // non-null only in tests / explicit override
    ): ResolvedOutbound
}

internal data class ResolvedOutbound(
    val transport: RouterTransport,
    val retryDelay: Duration,
)