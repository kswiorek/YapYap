package org.yapyap.persistence.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.Pending_syncs
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.SystemPayload
import kotlin.uuid.Uuid

internal data class PendingSyncRow(
    val syncId: Uuid,
    val roomId: RoomId,
    val targetMessageId: Uuid,
    val candidateAccounts: List<AccountId>,
)

/**
 * Orchestrator-owned: the messages we are missing and the accounts that may hold them.
 * Asking for them (which device, when) is the router's ([SyncAttemptStore]).
 */
internal interface PendingSyncRepository {

    /**
     * Inserts a new pending sync for [targetMessageId] with its candidate accounts.
     * At most one row per (room, target) exists; re-inserts are ignored.
     */
    suspend fun insertSync(
        syncId: Uuid,
        roomId: RoomId,
        targetMessageId: Uuid,
        candidateAccounts: List<AccountId>,
    )

    /** Deletes a pending sync by its [syncId]. Cascades to its candidates. */
    suspend fun deleteSync(syncId: Uuid)

    /** Deletes pending syncs targeting [targetMessageId] (satisfied by an arrival). */
    suspend fun deleteSyncsByTarget(roomId: RoomId, targetMessageId: Uuid)

    /**
     * Builds the wire request for [syncId]: the row's target plus the requester's
     * current chainable frontier as the responder's stop set (recomputed fresh on
     * every attempt, never stored). Returns null when the row is gone or its target
     * is already present (stale row — the caller should delete it).
     */
    suspend fun buildSyncRequest(syncId: Uuid): SystemPayload.SyncRequest?

    suspend fun candidateAccounts(syncId: Uuid): List<AccountId>

    /** Pending syncs [accountId] is a candidate for. */
    suspend fun syncIdsWithCandidate(accountId: AccountId): List<Uuid>

    suspend fun syncIdsInRoom(roomId: RoomId): List<Uuid>

    suspend fun allSyncIds(): List<Uuid>

    /**
     * Appends [accountIds] to [syncId]'s candidates (insert-if-absent). The
     * universal accumulation hook: any authenticated account that hands us
     * room content or asserts room membership (ping sender, message author) is
     * a candidate for the rows it touches — append-only, never replace, so one
     * trigger's candidates never evict another's.
     */
    suspend fun addCandidateAccounts(syncId: Uuid, accountIds: List<AccountId>)

    /**
     * Appends [accountIds] to every pending sync row in [roomId]
     * (insert-if-absent). The membership-refresh path: after a fold commit the
     * room's current ACTIVE members become candidates of all its live rows.
     */
    suspend fun appendCandidateAccountsForRoom(roomId: RoomId, accountIds: List<AccountId>)

    // Finds the sync targeting [targetMessageId] in the given [roomId].
    suspend fun findSyncByTarget(roomId: RoomId, targetMessageId: Uuid): PendingSyncRow?
}

internal class DefaultPendingSyncRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PendingSyncRepository {

    private val queries = database.syncQueries

    override suspend fun insertSync(
        syncId: Uuid,
        roomId: RoomId,
        targetMessageId: Uuid,
        candidateAccounts: List<AccountId>,
    ) {
        withContext(dbDispatcher) {
            queries.insertPendingSync(
                sync_id = syncId,
                room_id = roomId,
                target_message_id = targetMessageId,
            )
            candidateAccounts.forEach { accountId ->
                queries.insertPendingSyncCandidateAccount(sync_id = syncId, account_id = accountId)
            }
        }
    }

    override suspend fun deleteSync(syncId: Uuid) {
        withContext(dbDispatcher) {
            queries.deleteSync(syncId)
        }
    }

    override suspend fun deleteSyncsByTarget(roomId: RoomId, targetMessageId: Uuid) {
        withContext(dbDispatcher) {
            queries.deleteSyncsByTarget(roomId, targetMessageId)
        }
    }

    override suspend fun buildSyncRequest(syncId: Uuid): SystemPayload.SyncRequest? =
        withContext(dbDispatcher) {
            val row = queries.selectSyncById(syncId).executeAsOneOrNull() ?: return@withContext null
            if (database.messageQueries.selectMessageById(row.target_message_id).executeAsOneOrNull() != null) {
                return@withContext null // Target already present — stale row.
            }
            val knownIds = database.messageQueries.selectRoomFrontier(row.room_id)
                .executeAsList().map { it.message_id }
            SystemPayload.SyncRequest(
                roomId = row.room_id,
                syncId = row.sync_id,
                missingIds = listOf(row.target_message_id),
                knownIds = knownIds,
            )
        }

    override suspend fun candidateAccounts(syncId: Uuid): List<AccountId> =
        withContext(dbDispatcher) {
            queries.selectCandidateAccountsForSync(syncId).executeAsList()
        }

    override suspend fun syncIdsWithCandidate(accountId: AccountId): List<Uuid> =
        withContext(dbDispatcher) {
            queries.selectSyncIdsWithCandidate(accountId).executeAsList()
        }

    override suspend fun syncIdsInRoom(roomId: RoomId): List<Uuid> =
        withContext(dbDispatcher) {
            queries.selectSyncIdsInRoom(roomId).executeAsList()
        }

    override suspend fun allSyncIds(): List<Uuid> =
        withContext(dbDispatcher) {
            queries.selectAllSyncIds().executeAsList()
        }

    override suspend fun addCandidateAccounts(syncId: Uuid, accountIds: List<AccountId>) {
        withContext(dbDispatcher) {
            accountIds.forEach { accountId ->
                queries.insertPendingSyncCandidateAccount(syncId, accountId)
            }
        }
    }

    override suspend fun appendCandidateAccountsForRoom(roomId: RoomId, accountIds: List<AccountId>) {
        withContext(dbDispatcher) {
            accountIds.forEach { accountId ->
                queries.appendCandidateAccountsForRoom(accountId = accountId, roomId = roomId)
            }
        }
    }

    override suspend fun findSyncByTarget(roomId: RoomId, targetMessageId: Uuid): PendingSyncRow? =
        withContext(dbDispatcher) {
            queries.findSyncByTarget(roomId, targetMessageId).executeAsOneOrNull()?.toRow()
        }

    private fun Pending_syncs.toRow(): PendingSyncRow =
        PendingSyncRow(
            syncId = sync_id,
            roomId = room_id,
            targetMessageId = target_message_id,
            candidateAccounts = queries.selectCandidateAccountsForSync(sync_id).executeAsList(),
        )
}
