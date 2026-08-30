package org.yapyap.routing.ping

import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.messaging.RoomRepository

interface LamportSnapshotProvider {
    /** Most recent lamport clock per room (rooms.local_seq_n). Empty map on fresh installs. */
    suspend fun latestRoomLamports(): Map<RoomId, Long>
}

class DefaultLamportSnapshotProvider(
    private val roomRepository: RoomRepository,
) : LamportSnapshotProvider {
    override suspend fun latestRoomLamports(): Map<RoomId, Long> =
        TODO("roomRepository.allRoomLamports()")
}