package org.yapyap.persistence.sync

import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload
import kotlin.uuid.Uuid

/**
 * A pending sync request stored in the DB.
 *
 * The sync covers an inclusive lamport range [anchorLamport, orphanLamport].
 * - **Gap sync** (orphanLamport >= 0): requests messages in
 *   [anchorLamport, orphanLamport] to close a gap caused by an orphaned message.
 * - **Range sync** (orphanLamport == -1): requests all messages with
 *   lamport > anchorLamport (ping/pong-triggered catch-up).
 *
 * The wire [SyncRequest] is reconstructed from these columns at send time by
 * [org.yapyap.routing.sync.SyncRetryProcessor] — no payload BLOB is stored.
 */
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

    /**
     * Returns all pending **gap** syncs (orphanLamport >= 0) in [roomId] whose
     * inclusive range [anchorLamport, orphanLamport] contains [lamport].
     * Range syncs (orphanLamport == -1) are excluded.
     */
    suspend fun findGapSyncsContaining(roomId: String, lamport: Long): List<PendingSyncRow>

    /** Shrinks the anchor (lower bound) of a gap sync up to [anchorLamport]. */
    suspend fun updateAnchorLamport(syncId: Uuid, anchorLamport: Long)

    /** Shrinks the orphan (upper bound) of a gap sync down to [orphanLamport]. */
    suspend fun updateOrphanLamport(syncId: Uuid, orphanLamport: Long)

    /** Deletes a pending sync by its [syncId]. Cascades to candidate/attempted tables. */
    suspend fun deleteSync(syncId: Uuid)

    /** Returns true if a range sync (orphanLamport == -1) already exists for [roomId]. */
    suspend fun hasRangeSyncForRoom(roomId: String): Boolean

    // ---- retained for SyncRetryProcessor ----

    suspend fun earliestDueAt(): Long?
    suspend fun findDue(now: Long, limit: Int): List<PendingSyncRow>
    suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Long, now: Long)
    suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId>
    suspend fun accelerateForOnlinePeer(deviceId: PeerId, now: Long)
    suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Long)
    suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId)
    suspend fun findGapSyncsByAnchor(roomId: String, anchorLamport: Long): List<PendingSyncRow>
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

    override suspend fun findGapSyncsContaining(roomId: String, lamport: Long): List<PendingSyncRow> {
        TODO("Not yet implemented")
    }

    override suspend fun updateAnchorLamport(syncId: Uuid, anchorLamport: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun updateOrphanLamport(syncId: Uuid, orphanLamport: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteSync(syncId: Uuid) {
        TODO("Not yet implemented")
    }

    override suspend fun hasRangeSyncForRoom(roomId: String): Boolean {
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

    override suspend fun findGapSyncsByAnchor(roomId: String, anchorLamport: Long): List<PendingSyncRow> {
        TODO("Not yet implemented")
    }
}