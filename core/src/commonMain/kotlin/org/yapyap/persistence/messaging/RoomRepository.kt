package org.yapyap.persistence.messaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomType
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import kotlin.time.Clock

interface RoomRepository {
    suspend fun membersOfRoom(roomId: RoomId): List<AccountId>

    /** Rooms [peerId]'s account belongs to (drives which rooms we exchange frontiers about). */
    suspend fun roomsOfPeer(peerId: PeerId): List<RoomId>
    suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String)
    suspend fun addMember(roomId: RoomId, accountId: AccountId, role: RoomMemberRole)
    suspend fun removeMember(roomId: RoomId, accountId: AccountId)
}

class DefaultRoomRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : RoomRepository {
    override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> =
        withContext(dbDispatcher) {
            val members = database.roomQueries.selectAllMembersForRoom(roomId)
                .executeAsList()
                .map { it.account_id }
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

    override suspend fun roomsOfPeer(peerId: PeerId): List<RoomId> =
        withContext(dbDispatcher) {
            database.roomQueries.selectRoomsOfPeer(peerId).executeAsList()
        }

    override suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String) {
        withContext(dbDispatcher) {
            database.roomQueries.ensureRoomExists(roomId, type, name)
            //TODO: check if room actually created
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.ROOM_CREATED,
                message = "Ensured room row exists",
                fields = mapOf(
                    "roomId" to roomId,
                    "type" to type,
                ),
            )
        }
    }

    override suspend fun addMember(roomId: RoomId, accountId: AccountId, role: RoomMemberRole) {
        withContext(dbDispatcher) {
            database.roomQueries.insertRoomMember(roomId, accountId, role, Clock.System.now())
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.ROOM_MEMBERS_QUERIED,
                message = "Added room member",
                fields = mapOf(
                    "roomId" to roomId,
                    "accountId" to accountId,
                    "role" to role,
                ),
            )
        }
    }

    override suspend fun removeMember(roomId: RoomId, accountId: AccountId) {
        withContext(dbDispatcher) {
            database.roomQueries.removeRoomMember(roomId, accountId)
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.ROOM_MEMBERS_QUERIED,
                message = "Removed room member",
                fields = mapOf(
                    "roomId" to roomId,
                    "accountId" to accountId,
                ),
            )
        }
    }
}
