package org.yapyap.persistence.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.Pending_syncs
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class PendingSyncRow(
    val syncId: Uuid,
    val roomId: RoomId,
    val targetMessageId: Uuid,
    val candidateAccounts: List<AccountId>,
    val attemptedDevices: Set<PeerId>,
    val attempts: Int
)

interface PendingSyncRepository {

    /**
     * Inserts a new pending sync for [targetMessageId] with its candidate accounts.
     * At most one row per (room, target) exists; re-inserts are ignored.
     * [nextAttemptAt] controls when the retry processor will first try to send it.
     */
    suspend fun insertSync(
        syncId: Uuid,
        roomId: RoomId,
        targetMessageId: Uuid,
        candidateAccounts: List<AccountId>,
        nextAttemptAt: Instant,
    )

    /** Deletes a pending sync by its [syncId]. Cascades to candidate/attempted tables. */
    suspend fun deleteSync(syncId: Uuid)

    /** Deletes pending syncs targeting [targetMessageId] (satisfied by an arrival). */
    suspend fun deleteSyncsByTarget(roomId: RoomId, targetMessageId: Uuid)

    /**
     * Builds the wire request for [syncId]: the row's target plus the requester's
     * current chainable frontier as the responder's stop set (recomputed fresh on
     * every attempt, never stored). Returns null when the target is already present
     * (stale row — the caller should delete it).
     */
    suspend fun buildSyncRequest(syncId: Uuid): SystemPayload.SyncRequest?

    // ---- retained for SyncRetryProcessor ----

    suspend fun earliestDueAt(): Instant?
    suspend fun findDue(now: Instant, limit: Int): List<PendingSyncRow>
    suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Instant)
    suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId>
    suspend fun accelerateForOnlinePeer(deviceId: PeerId, at: Instant)
    suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Instant)
    suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId)

    // Finds the sync targeting [targetMessageId] in the given [roomId].
    suspend fun findSyncByTarget(roomId: RoomId, targetMessageId: Uuid): PendingSyncRow?
}

class DefaultPendingSyncRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PendingSyncRepository {

    private val queries = database.syncQueries

    override suspend fun insertSync(
        syncId: Uuid,
        roomId: RoomId,
        targetMessageId: Uuid,
        candidateAccounts: List<AccountId>,
        nextAttemptAt: Instant,
    ) {
        withContext(dbDispatcher) {
            queries.insertPendingSync(
                sync_id = syncId,
                room_id = roomId,
                target_message_id = targetMessageId,
                next_attempt_at = nextAttemptAt,
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

    override suspend fun earliestDueAt(): Instant? =
        withContext(dbDispatcher) {
            queries.selectEarliestDueAt().executeAsOneOrNull()?.MIN
        }

    override suspend fun findDue(now: Instant, limit: Int): List<PendingSyncRow> =
        withContext(dbDispatcher) {
            queries.selectDueSyncs(now, limit.toLong()).executeAsList().map { it.toRow() }
        }

    override suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Instant) {
        withContext(dbDispatcher) {
            queries.recordAttempt(next_attempt_at = nextAttemptAt, sync_id = syncId)
        }
    }

    override suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId> =
        withContext(dbDispatcher) {
            queries.selectAttemptedPeersForSync(syncId).executeAsList().toSet()
        }

    override suspend fun accelerateForOnlinePeer(deviceId: PeerId, at: Instant) {
        withContext(dbDispatcher) {
            queries.accelerateForOnlinePeer(at, deviceId)
        }
    }

    override suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Instant) {
        withContext(dbDispatcher) {
            queries.updateNextAttemptAt(nextAttemptAt, syncId)
        }
    }

    override suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId) {
        withContext(dbDispatcher) {
            queries.insertAttemptedPeer(syncId, deviceId)
        }
    }

    override suspend fun findSyncByTarget(roomId: RoomId, targetMessageId: Uuid): PendingSyncRow? =
        withContext(dbDispatcher) {
            queries.findSyncByTarget(roomId, targetMessageId).executeAsOneOrNull()?.toRow()
        }

    private fun Pending_syncs.toRow(): PendingSyncRow {
        val candidates = queries.selectCandidateAccountsForSync(sync_id).executeAsList()
        val attempted = queries.selectAttemptedPeersForSync(sync_id).executeAsList().toSet()
        return PendingSyncRow(
            syncId = sync_id,
            roomId = room_id,
            targetMessageId = target_message_id,
            candidateAccounts = candidates,
            attemptedDevices = attempted,
            attempts = attempts.toInt(),
        )
    }
}
