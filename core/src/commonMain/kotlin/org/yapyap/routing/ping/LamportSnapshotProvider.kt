package org.yapyap.routing.ping

import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.PeerId

interface LamportSnapshotProvider {
    /** Most recent lamport clock per room (rooms.local_seq_n). Empty map on fresh installs. */
    suspend fun latestRoomLamports(peerId: PeerId): List<Pair<RoomId, Long>>
}

class DefaultLamportSnapshotProvider(
    private val roomRepository: RoomRepository,
) : LamportSnapshotProvider {
    override suspend fun latestRoomLamports(peerId: PeerId): List<Pair<RoomId, Long>> =
        roomRepository.getLocalSeqForPeer(peerId)
}