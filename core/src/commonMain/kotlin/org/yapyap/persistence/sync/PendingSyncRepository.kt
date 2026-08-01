package org.yapyap.persistence.sync

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import kotlin.uuid.Uuid

data class PendingSyncRow(
    val syncId: Uuid,
    val roomId: String,
    val requestPayload: ByteArray,
    val candidateAccounts: List<AccountId>,
    val attemptedDevices: Set<PeerId>,
    val attempts: Int,
)

interface PendingSyncRepository {
    suspend fun insertSync(syncRequest: SyncRequest, candidateAccounts: List<AccountId>, nextAttemptAt: Long)
    suspend fun deleteByMissingAncestorIds(ancestorIds: List<Uuid>)

    suspend fun earliestDueAt(): Long?
    suspend fun findDue(now: Long, limit: Int): List<PendingSyncRow>
    suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Long, now: Long)
    suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId>
    suspend fun accelerateForOnlinePeer(deviceId: PeerId, now: Long)
    suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Long)
    suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId)
}

class DefaultPendingSyncRepository(
    private val database: YapYapDatabase,
) : PendingSyncRepository {
    override suspend fun insertSync(
        syncRequest: SyncRequest,
        candidateAccounts: List<AccountId>,
        nextAttemptAt: Long
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteByMissingAncestorIds(ancestorIds: List<Uuid>) {
        TODO("Not yet implemented")
    }

    override suspend fun earliestDueAt(): Long? {
        TODO("Not yet implemented")
    }

    override suspend fun findDue(
        now: Long,
        limit: Int
    ): List<PendingSyncRow> {
        TODO("Not yet implemented")
    }

    override suspend fun recordAttempt(
        syncId: Uuid,
        nextAttemptAt: Long,
        now: Long
    ) {
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
}