package org.yapyap.routing.ping

import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.PeerId
import kotlin.uuid.Uuid

interface FrontierSnapshotProvider {
    /** Chainable frontier tip IDs per room the peer belongs to. Empty map on fresh installs. */
    suspend fun latestRoomFrontiers(peerId: PeerId): List<Pair<RoomId, List<Uuid>>>
}

class DefaultFrontierSnapshotProvider(
    private val roomRepository: RoomRepository,
    private val messageRepository: MessageRepository,
) : FrontierSnapshotProvider {
    override suspend fun latestRoomFrontiers(peerId: PeerId): List<Pair<RoomId, List<Uuid>>> =
        roomRepository.roomsOfPeer(peerId).map { roomId ->
            roomId to messageRepository.findRoomFrontier(roomId).map { it.payload.messageId }
        }
}
