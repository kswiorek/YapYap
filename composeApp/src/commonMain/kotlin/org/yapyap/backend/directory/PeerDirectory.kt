package org.yapyap.backend.directory

import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

interface PeerDirectory {
    fun allPeers(): List<PeerDescriptor>
    fun getByDeviceId(deviceId: String): PeerDescriptor?
    fun getByPeerId(peerId: PeerId): PeerDescriptor? = getByDeviceId(peerId.deviceId)
    fun requireByPeerId(peerId: PeerId): PeerDescriptor =
        requireNotNull(getByPeerId(peerId)) { "Unknown peer for deviceId=${peerId.deviceId}" }

    fun resolveTorEndpoint(peerId: PeerId): TorEndpoint = requireByPeerId(peerId).torEndpoint
}

class InMemoryPeerDirectory(
    peers: List<PeerDescriptor> = emptyList(),
) : PeerDirectory {
    private val peersByDeviceId: MutableMap<String, PeerDescriptor> =
        peers.associateBy { it.id.deviceId }.toMutableMap()

    override fun allPeers(): List<PeerDescriptor> = peersByDeviceId.values.toList()

    override fun getByDeviceId(deviceId: String): PeerDescriptor? = peersByDeviceId[deviceId]

    fun upsert(peer: PeerDescriptor) {
        peersByDeviceId[peer.id.deviceId] = peer
    }
}
