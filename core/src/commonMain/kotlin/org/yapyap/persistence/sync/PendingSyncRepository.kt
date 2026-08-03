package org.yapyap.persistence.sync

import org.yapyap.crypto.identity.AccountId
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
    suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Long, now: Long)
    suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId>
    suspend fun accelerateForOnlinePeer(deviceId: PeerId, now: Long)
    suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Long)
    suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId)

    // Finds the sync with the given [anchorLamport] in the given [roomId].
    suspend fun findGapSyncByAnchor(roomId: String, anchorLamport: Long): PendingSyncRow?
}

class DefaultPendingSyncRepository(
    private val database: org.yapyap.persistence.YapYapDatabase,
) : PendingSyncRepository {

    override suspend fun insertSync(
        syncId: Uuid,
        roomId: String,
        maxMessages: Int,
        anchorLamport: Long,
        orphanLamport: Long,
        candidateAccounts: List<AccountId>,
        nextAttemptAt: Long,
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun updateOrphanLamport(syncId: Uuid, orphanLamport: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteSync(syncId: Uuid) {
        TODO("Not yet implemented")
    }

    override suspend fun earliestDueAt(): Long? {
        TODO("Not yet implemented")
    }

    override suspend fun findDue(now: Long, limit: Int): List<PendingSyncRow> {
        TODO("Not yet implemented")
    }

    override suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Long, now: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId> {
        TODO("Not yet implemented")
    }

    override suspend fun accelerateForOnlinePeer(deviceId: PeerId, now: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId) {
        TODO("Not yet implemented")
    }

    override suspend fun findGapSyncByAnchor(
        roomId: String,
        anchorLamport: Long
    ): PendingSyncRow? {
        TODO("Not yet implemented")
    }
}