package org.yapyap.routing.policy

import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.RoutingContext

/**
 * Asks only devices with an open link, in transport preference order, like every other send:
 * a request to a device without one would wait in the outbox for a link that may never come,
 * while another candidate could have answered. With none linked, nobody is asked until a
 * candidate's link comes up.
 */
internal class DefaultSyncPeerPolicy(
    private val ctx: RoutingContext,
) : SyncPeerPolicy {

    override fun pickNextDevice(
        candidates: List<PeerId>,
        declined: Set<PeerId>,
        unanswered: Set<PeerId>,
    ): PeerId? {
        val eligible = candidates.filterNot { it in declined }
        return firstLinked(eligible.filterNot { it in unanswered }) ?: firstLinked(eligible)
    }

    private fun firstLinked(devices: List<PeerId>): PeerId? {
        for (transport in ctx.routerConfig.value.linkPreference) {
            devices.firstOrNull { ctx.peerLinks.sessions(transport).hasSession(it) }?.let { return it }
        }
        return null
    }
}
