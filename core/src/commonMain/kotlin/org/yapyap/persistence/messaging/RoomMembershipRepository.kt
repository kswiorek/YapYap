package org.yapyap.persistence.messaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher

interface RoomMembershipRepository {
    suspend fun membersOfRoom(roomId: String): List<AccountId>
}

class DefaultRoomMembershipRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : RoomMembershipRepository {
    override suspend fun membersOfRoom(roomId: String): List<AccountId> =
        withContext(dbDispatcher) {
            database.roomQueries.selectAllMembersForRoom(roomId)
                .executeAsList()
                .map { AccountId(it.account_id) }
        }
}