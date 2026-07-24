package org.yapyap.persistence.messaging

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.YapYapDatabase

interface RoomMembershipRepository {
    fun membersOfRoom(roomId: String): List<AccountId>
}

class DefaultRoomMembershipRepository(
    private val database: YapYapDatabase,
) : RoomMembershipRepository {
    override fun membersOfRoom(roomId: String): List<AccountId> =
        database.roomQueries.selectAllMembersForRoom(roomId)
            .executeAsList()
            .map { AccountId(it.account_id) }
}