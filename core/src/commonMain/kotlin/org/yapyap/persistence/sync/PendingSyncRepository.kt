package org.yapyap.persistence.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.Pending_syncs
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload
import kotlin.uuid.Uuid

data class PendingSyncRow(
    val syncId: Uuid,
    val roomId: String,
    val maxMessages: Int,
    val anchorLamport: Long,
    val orphanLamport: Long,
    val candidateAccounts: List<AccountId>,
    val attemptedDevices: Set<PeerId>,
    val attempts: Int
){
    fun toSyncRequest(): SystemPayload.SyncRequest = SystemPayload.SyncRequest(
        roomId = roomId,
        syncId = syncId,
        anchorLamport = anchorLamport,
        orphanLamport = orphanLamport,
        maxMessages = maxMessages,
    )
}

interface PendingSyncRepository {

    /**
     * Inserts a new pending sync with its candidate accounts.
     * [nextAttemptAt] controls when the retry processor will first try to send it.
     */
    suspend fun insertSync(
        syncId: Uuid,
        roomId: String,
        maxMessages: Int,
        anchorLamport: Long,
        orphanLamport: Long,
        candidateAccounts: List<AccountId>,
        nextAttemptAt: Long,
    )

    /** updates  the [orphanLamport] for a sync with [syncId]. */
    suspend fun updateOrphanLamport(syncId: Uuid, orphanLamport: Long)

    /** Deletes a pending sync by its [syncId]. Cascades to candidate/attempted tables. */
    suspend fun deleteSync(syncId: Uuid)

    // ---- retained for SyncRetryProcessor ----

    suspend fun earliestDueAt(): Long?
    suspend fun findDue(now: Long, limit: Int): List<PendingSyncRow>
    suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Long)
    suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId>
    suspend fun accelerateForOnlinePeer(deviceId: PeerId, now: Long)
    suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Long)
    suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId)

    // Finds the sync with the given [anchorLamport] in the given [roomId].
    suspend fun findGapSyncByAnchor(roomId: String, anchorLamport: Long): PendingSyncRow?
}

class DefaultPendingSyncRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PendingSyncRepository {

    private val queries = database.syncQueries

    override suspend fun insertSync(
        syncId: Uuid,
        roomId: String,
        maxMessages: Int,
        anchorLamport: Long,
        orphanLamport: Long,
        candidateAccounts: List<AccountId>,
        nextAttemptAt: Long,
    ) {
        withContext(dbDispatcher) {
            queries.insertPendingSync(
                sync_id = syncId,
                room_id = roomId,
                max_messages = maxMessages.toLong(),
                anchor_lamport = anchorLamport,
                orphan_lamport = orphanLamport,
                next_attempt_at = nextAttemptAt,
            )
            candidateAccounts.forEach { accountId ->
                queries.insertPendingSyncCandidateAccount(sync_id = syncId, account_id = accountId)
            }
        }
    }

    override suspend fun updateOrphanLamport(syncId: Uuid, orphanLamport: Long) {
        withContext(dbDispatcher) {
            queries.updateOrphanLamport(orphanLamport, syncId)
        }
    }

    override suspend fun deleteSync(syncId: Uuid) {
        withContext(dbDispatcher) {
            queries.deleteSync(syncId)
        }
    }

    override suspend fun earliestDueAt(): Long? =
        withContext(dbDispatcher) {
            queries.selectEarliestDueAt().executeAsOneOrNull()?.MIN
        }

    override suspend fun findDue(now: Long, limit: Int): List<PendingSyncRow> =
        withContext(dbDispatcher) {
            queries.selectDueSyncs(now, limit.toLong()).executeAsList().map { it.toRow() }
        }

    override suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Long) {
        withContext(dbDispatcher) {
            queries.recordAttempt(next_attempt_at = nextAttemptAt, sync_id = syncId)
        }
    }

    override suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId> =
        withContext(dbDispatcher) {
            queries.selectAttemptedPeersForSync(syncId).executeAsList().toSet()
        }

    override suspend fun accelerateForOnlinePeer(deviceId: PeerId, now: Long) {
        withContext(dbDispatcher) {
            queries.accelerateForOnlinePeer(now, deviceId)
        }
    }

    override suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Long) {
        withContext(dbDispatcher) {
            queries.updateNextAttemptAt(nextAttemptAt, syncId)
        }
    }

    override suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId) {
        withContext(dbDispatcher) {
            queries.insertAttemptedPeer(syncId, deviceId)
        }
    }

    override suspend fun findGapSyncByAnchor(roomId: String, anchorLamport: Long): PendingSyncRow? =
        withContext(dbDispatcher) {
            queries.findGapSyncsByAnchor(roomId, anchorLamport).executeAsList().firstOrNull()?.toRow()
        }

    private fun Pending_syncs.toRow(): PendingSyncRow {
        val candidates = queries.selectCandidateAccountsForSync(sync_id).executeAsList()
        val attempted = queries.selectAttemptedPeersForSync(sync_id).executeAsList().toSet()
        return PendingSyncRow(
            syncId = sync_id,
            roomId = room_id,
            maxMessages = max_messages.toInt(),
            anchorLamport = anchor_lamport,
            orphanLamport = orphan_lamport,
            candidateAccounts = candidates,
            attemptedDevices = attempted,
            attempts = attempts.toInt(),
        )
    }
}