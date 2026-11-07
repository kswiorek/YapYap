package org.yapyap.orchestrator.sync

import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.store.RoomRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.routing.ping.FrontierSnapshotProvider
import kotlin.uuid.Uuid

internal class DefaultFrontierSnapshotProvider(
    private val roomRepository: RoomRepository,
    private val messageRepository: MessageRepository,
    private val localDeviceId: PeerId,
) : FrontierSnapshotProvider {
    override suspend fun latestRoomFrontiers(account: AccountId): List<Pair<RoomId, List<Uuid>>> {
        // Shared rooms only: advertise a room iff both sides are ACTIVE members.
        // A converged removed member stops advertising the room — which is what
        // self-extinguishes the ping-contradiction re-push loop (docs/room
        // events.md §6). GLOBAL is unaffected: the local account is ACTIVE there.
        val own = roomRepository.roomsOfPeer(localDeviceId).toSet()
        return roomRepository.roomsOfAccount(account)
            .filter { it in own }
            .map { roomId ->
                roomId to messageRepository.findRoomFrontier(roomId).map { it.payload.messageId }
            }
    }
}
