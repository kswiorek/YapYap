package org.yapyap.persistence.sync

import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import kotlin.uuid.Uuid

data class PendingSyncRow(
    val syncId: Uuid,
    val roomId: String,
    val requestPayload: ByteArray,
    val candidateAccounts: List<AccountId>,
    val attempts: Int,
)

interface PendingSyncRepository {
    suspend fun insertSync(syncRequest: SyncRequest, candidateAccounts: List<AccountId>)
    suspend fun deleteByMissingAncestorIds(ancestorIds: List<Uuid>)

    suspend fun earliestDueAt(): Long?
    suspend fun findDue(now: Long, limit: Int): List<PendingSyncRow>
    suspend fun markInFlight(syncId: Uuid, deviceId: PeerId, now: Long)
    suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Long, now: Long)
    suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId>
    suspend fun markAbandoned(syncId: Uuid)
    suspend fun accelerateForOnlinePeer(deviceId: PeerId, now: Long)
    suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Long)
}

class DefaultPendingSyncRepository(
) : PendingSyncRepository {
    override suspend fun insertSync(
        syncRequest: SyncRequest,
        candidateAccounts: List<AccountId>
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

    override suspend fun markInFlight(
        syncId: Uuid,
        deviceId: PeerId,
        now: Long
    ) {
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

    override suspend fun markAbandoned(syncId: Uuid) {
        TODO("Not yet implemented")
    }

    override suspend fun accelerateForOnlinePeer(deviceId: PeerId, now: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Long) {
        TODO("Not yet implemented")
    }
}