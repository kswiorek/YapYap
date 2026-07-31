package org.yapyap.routing.policy

import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.PeerAvailabilityRegistry

internal class DefaultSyncPeerPolicy(
    private val peerAvailabilityRegistry: PeerAvailabilityRegistry,
): SyncPeerPolicy {
    override fun pickNextDevice(
        candidates: List<PeerId>,
        attempted: Set<PeerId>
    ): PeerId? {
        TODO("Not yet implemented")
        //Has webRTC session?
        //Is PeerAvailabilityRegistry.onlineNow()
        //last_seen_timestamp showing recent
    }
}