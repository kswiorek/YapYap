package org.yapyap.persistence.messaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher

interface RoomRepository {
    suspend fun membersOfRoom(roomId: RoomId): List<AccountId>
    suspend fun updateLocalSeq(roomId: RoomId, seqN: Long)
    suspend fun getLocalSeq(roomId: RoomId): Long?
}

class DefaultRoomRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : RoomRepository {
    override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> =
        withContext(dbDispatcher) {
            val members = database.roomQueries.selectAllMembersForRoom(roomId)
                .executeAsList()
                .map { AccountId(it.account_id) }
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.ROOM_MEMBERS_QUERIED,
                message = "Fetched room members",
                fields = mapOf(
                    "roomId" to roomId,
                    "memberCount" to members.size,
                ),
            )
            members
        }

    override suspend fun updateLocalSeq(roomId: RoomId, seqN: Long) {
        withContext(dbDispatcher) {
            database.roomQueries.updateRoomLocalSeq(seqN, roomId)
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.ROOM_LOCAL_SEQ_UPDATED,
                message = "Updated room local sequence",
                fields = mapOf(
                    "roomId" to roomId,
                    "seqN" to seqN,
                ),
            )
        }
    }

    override suspend fun getLocalSeq(roomId: RoomId): Long? =
        withContext(dbDispatcher) {
            val localSeq = database.roomQueries.selectRoomLocalSeq(roomId).executeAsOneOrNull()
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.ROOM_LOCAL_SEQ_QUERIED,
                message = "Queried room local sequence",
                fields = mapOf(
                    "roomId" to roomId,
                    "seqN" to (localSeq ?: "null"),
                ),
            )
            localSeq
        }
}