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
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.persistence.db.RoomType
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** One committed `room_members` row: the fold's member-at-some-point record. */
data class RoomMemberRecord(
    val accountId: AccountId,
    val role: RoomMemberRole,
    val status: RoomMemberStatus,
    /**
     * Defining `MemberRemove` node when [status] is REMOVED (the removal boundary:
     * bounded sync serve + ping-contradiction re-push). Null on ACTIVE rows and on
     * GLOBAL rows (no room-DAG removal exists there).
     */
    val removalNodeId: Uuid? = null,
)

/** One `rooms` row: genesis-merged display state (provisional UNKNOWN until the fold commits). */
data class RoomRecord(
    val roomId: RoomId,
    val spaceId: String?,
    val type: RoomType,
    val name: String,
)

interface RoomRepository {
    /**
     * ACTIVE members of [roomId] (access read: sync gate, fan-out, sync
     * candidates). REMOVED rows never grant access — removal cuts sync from the
     * first committed REMOVED row (docs/room events.md §5).
     */
    suspend fun membersOfRoom(roomId: RoomId): List<AccountId>

    /**
     * Every member row the fold committed for [roomId], ACTIVE and REMOVED alike
     * (GUI/badge read). A missing account means never-a-member at fold position;
     * rows for accounts whose identity has not landed yet are absent until the
     * re-fold commits them (projection deferral, docs/room events.md §5).
     */
    suspend fun memberStatusesOfRoom(roomId: RoomId): List<RoomMemberRecord>

    /**
     * The fold-committed row for [accountId] in [roomId], or null (never-member at
     * fold position, or not yet folded). The REMOVED leg of the sync-gate
     * trichotomy and the ping-contradiction re-push read this.
     */
    suspend fun memberRowOf(roomId: RoomId, accountId: AccountId): RoomMemberRecord?

    /** Rooms [peerId]'s account belongs to (drives which rooms we exchange frontiers about). */
    suspend fun roomsOfPeer(peerId: PeerId): List<RoomId>
    suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String)

    /**
     * The fold recompute's row writer (docs/room events.md §5): upserts one
     * fold-output member row — ACTIVE or REMOVED alike (rows are retained on
     * removal). [removalNodeId] is the defining `MemberRemove` node, non-null
     * exactly on chat REMOVED rows; GLOBAL rows always pass null.
     */
    suspend fun upsertMember(
        roomId: RoomId,
        accountId: AccountId,
        role: RoomMemberRole,
        status: RoomMemberStatus = RoomMemberStatus.ACTIVE,
        removalNodeId: Uuid? = null,
    )

    /**
     * GLOBAL-tier op (the global projector owns GLOBAL rows). Flips the row to
     * REMOVED and retains it — access readers filter ACTIVE, so removal still
     * cuts sync access; chat rows flip status via the room projector.
     */
    suspend fun removeMember(roomId: RoomId, accountId: AccountId)

    /** Chat room ids (GLOBAL excluded) — the room projector's boot + GLOBAL-commit sweep. */
    suspend fun allChatRoomIds(): List<RoomId>

    /** The `rooms` row, or null when the room is unknown locally. */
    suspend fun roomOf(roomId: RoomId): RoomRecord?

    /**
     * Genesis merge for the `rooms` row (docs/room events.md §5): targeted update of
     * name/type, never INSERT OR REPLACE (preserves local-only columns). A non-null
     * [spaceId] writes only once its spaces row exists — deferred otherwise (FK) and
     * retried on later folds.
     */
    suspend fun mergeRoomFromGenesis(roomId: RoomId, name: String, type: RoomType, spaceId: String?)

    /**
     * Defensive convergence for the `room_members` recompute: rows outside the fold
     * output shouldn't exist (`members.keys` retains REMOVED); the delete keeps the
     * projection exactly equal to the fold output.
     */
    suspend fun removeRoomMembersNotIn(roomId: RoomId, keep: Collection<AccountId>)
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

    override suspend fun memberStatusesOfRoom(roomId: RoomId): List<RoomMemberRecord> =
        withContext(dbDispatcher) {
            database.roomQueries.selectMemberStatusesForRoom(roomId)
                .executeAsList()
                .map { RoomMemberRecord(it.account_id, it.role, it.status, it.removal_node_id) }
        }

    override suspend fun memberRowOf(roomId: RoomId, accountId: AccountId): RoomMemberRecord? =
        withContext(dbDispatcher) {
            database.roomQueries.selectMemberRow(roomId, accountId)
                .executeAsOneOrNull()
                ?.let { RoomMemberRecord(it.account_id, it.role, it.status, it.removal_node_id) }
        }

    override suspend fun roomsOfPeer(peerId: PeerId): List<RoomId> =
        withContext(dbDispatcher) {
            database.roomQueries.selectRoomsOfPeer(peerId).executeAsList()
        }

    override suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String) {
        withContext(dbDispatcher) {
            database.roomQueries.ensureRoomExists(roomId, type, name)
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

    override suspend fun upsertMember(
        roomId: RoomId,
        accountId: AccountId,
        role: RoomMemberRole,
        status: RoomMemberStatus,
        removalNodeId: Uuid?,
    ) {
        withContext(dbDispatcher) {
            database.roomQueries.insertRoomMember(roomId, accountId, role, status, removalNodeId, Clock.System.now())
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.ROOM_MEMBERS_QUERIED,
                message = "Upserted room member",
                fields = mapOf(
                    "roomId" to roomId,
                    "accountId" to accountId,
                    "role" to role,
                    "status" to status,
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

    override suspend fun allChatRoomIds(): List<RoomId> =
        withContext(dbDispatcher) {
            database.roomQueries.selectAllChatRoomIds(RoomId.GLOBAL).executeAsList()
        }

    override suspend fun roomOf(roomId: RoomId): RoomRecord? =
        withContext(dbDispatcher) {
            database.roomQueries.selectRoomById(roomId)
                .executeAsOneOrNull()
                ?.let { RoomRecord(it.room_id, it.space_id, it.type, it.name) }
        }

    override suspend fun mergeRoomFromGenesis(
        roomId: RoomId,
        name: String,
        type: RoomType,
        spaceId: String?,
    ) {
        withContext(dbDispatcher) {
            database.roomQueries.updateRoomFromGenesis(name, type, roomId)
            if (spaceId != null) {
                // Deferred until the space row exists (FK): a later fold retries.
                runCatching { database.roomQueries.updateRoomSpaceId(spaceId, roomId) }
                    .onFailure {
                        AppLog.debug(
                            component = LogComponent.DATABASE,
                            event = LogEvent.ROOM_CREATED,
                            message = "Deferred room space write — space row absent",
                            fields = mapOf(
                                "roomId" to roomId,
                                "spaceId" to spaceId,
                            ),
                        )
                    }
            }
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.ROOM_CREATED,
                message = "Merged room row from genesis",
                fields = mapOf(
                    "roomId" to roomId,
                    "name" to name,
                ),
            )
        }
    }

    override suspend fun removeRoomMembersNotIn(roomId: RoomId, keep: Collection<AccountId>) {
        withContext(dbDispatcher) {
            if (keep.isEmpty()) {
                database.roomQueries.deleteAllRoomMembers(roomId)
            } else {
                database.roomQueries.deleteRoomMembersNotIn(roomId, keep)
            }
        }
    }
}
