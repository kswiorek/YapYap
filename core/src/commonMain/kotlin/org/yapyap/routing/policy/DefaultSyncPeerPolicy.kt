package org.yapyap.routing.policy

import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RoutingContext

internal class DefaultSyncPeerPolicy(
    private val ctx: RoutingContext,
    private val peerAvailabilityRegistry: PeerAvailabilityRegistry,
) : SyncPeerPolicy {

    override fun pickNextDevice(
        candidates: List<PeerId>,
        attempted: Set<PeerId>,
    ): PeerId? {
        val eligible = candidates.filterNot { it in attempted }
        if (eligible.isEmpty()) return null

        // Tier 1: established WebRTC session — direct, zero-setup path, lowest latency.
        eligible.firstOrNull { ctx.webRtcTransport.hasSession(it) }?.let { return it }

        // Tier 2: reachable right now (best chance of a completed round-trip without a session).
        eligible.firstOrNull { peerAvailabilityRegistry.isOnline(it) }?.let { return it }

        // No peers available.
        return null
    }
}