package org.yapyap.routing.ping

import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import kotlin.uuid.Uuid

internal interface FrontierSnapshotProvider {
    /** Chainable frontier tip IDs per room the peer belongs to. Empty map on fresh installs. */
    suspend fun latestRoomFrontiers(peerId: PeerId): List<Pair<RoomId, List<Uuid>>>
}

internal class DefaultFrontierSnapshotProvider(
    private val roomRepository: RoomRepository,
    private val messageRepository: MessageRepository,
    private val localDeviceId: PeerId,
) : FrontierSnapshotProvider {
    override suspend fun latestRoomFrontiers(peerId: PeerId): List<Pair<RoomId, List<Uuid>>> {
        // Shared rooms only: advertise a room iff both sides are ACTIVE members.
        // A converged removed member stops advertising the room — which is what
        // self-extinguishes the ping-contradiction re-push loop (docs/room
        // events.md §6). GLOBAL is unaffected: the local account is ACTIVE there.
        val own = roomRepository.roomsOfPeer(localDeviceId).toSet()
        return roomRepository.roomsOfPeer(peerId)
            .filter { it in own }
            .map { roomId ->
                roomId to messageRepository.findRoomFrontier(roomId).map { it.payload.messageId }
            }
    }
}
