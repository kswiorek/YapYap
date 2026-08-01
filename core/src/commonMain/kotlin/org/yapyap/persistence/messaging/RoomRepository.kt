package org.yapyap.persistence.messaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher

interface RoomRepository {
    suspend fun membersOfRoom(roomId: String): List<AccountId>
    suspend fun updateLocalSeq(roomId: String, seqN: Long)
    suspend fun getLocalSeq(roomId: String): Long?
}

class DefaultRoomRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : RoomRepository {
    override suspend fun membersOfRoom(roomId: String): List<AccountId> =
        withContext(dbDispatcher) {
            database.roomQueries.selectAllMembersForRoom(roomId)
                .executeAsList()
                .map { AccountId(it.account_id) }
        }

    override suspend fun updateLocalSeq(roomId: String, seqN: Long) {
        withContext(dbDispatcher) {
            database.roomQueries.updateRoomLocalSeq(seqN, roomId)
        }
    }

    override suspend fun getLocalSeq(roomId: String): Long? =
        withContext(dbDispatcher) {
            database.roomQueries.selectRoomLocalSeq(roomId).executeAsOneOrNull()
        }
}