package org.yapyap.backend.routing

import org.yapyap.backend.directory.PeerDirectory
import org.yapyap.backend.protocol.EnvelopeRoute
import org.yapyap.backend.protocol.PeerId

/**
 * Temporary routing helper while full router/inbox orchestration is not implemented.
 */
class PlaceholderRouter(
    private val peerDirectory: PeerDirectory,
) {
    fun routeForTarget(target: PeerId): EnvelopeRoute {
        val descriptor = peerDirectory.requireByPeerId(target)
        return EnvelopeRoute(
            destinationAccount = descriptor.id.accountId,
            destinationDevice = descriptor.id.deviceId,
            nextHopDevice = null,
        )
    }
}
