package org.yapyap.routing.policy

import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.RouterTransport
import kotlin.time.Duration

internal interface OutboundPolicy {
    /**
     * Picks the transport for one send to [target], given the transports with a link open to
     * it right now ([org.yapyap.routing.link.PeerLinks.linksOf]).
     */
    fun resolve(
        target: PeerId,
        links: Set<RouterTransport>,
        retries: Long,
        forced: RouterTransport? = null,      // non-null only in tests / explicit override
    ): ResolvedOutbound
}

/**
 * @param transport the open link to use; null when no link is open — the caller waits for
 *   one (outbox) or falls back to a one-shot Tor dial (system traffic, bootstrap).
 */
internal data class ResolvedOutbound(
    val transport: RouterTransport?,
    val retryDelay: Duration,
)
