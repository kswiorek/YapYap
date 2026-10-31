package org.yapyap.routing.policy

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RouterTransport

/** Uses the most preferred open link (`RouterConfig.linkPreference`). */
internal class LinkPreferencePolicy(
    private val config: StateFlow<RouterConfig>,
) : OutboundPolicy {
    override fun resolve(
        target: PeerId,
        links: Set<RouterTransport>,
        retries: Long,
        forced: RouterTransport?
    ): ResolvedOutbound {
        val configSnapshot = config.value
        val transport = forced ?: configSnapshot.linkPreference.firstOrNull { it in links }

        var retryDelay = configSnapshot.standbyRetryDelay

        if (retries <= configSnapshot.fastRetryBudget) {
            retryDelay = configSnapshot.getRetryDelay(transport ?: RouterTransport.TOR)
        }

        return ResolvedOutbound(transport, retryDelay)
    }
}
