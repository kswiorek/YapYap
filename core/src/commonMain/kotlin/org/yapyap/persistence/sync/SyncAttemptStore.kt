package org.yapyap.persistence.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal data class SyncAttemptRow(
    val syncId: Uuid,
    val attempts: Int,
    val declinedDevices: Set<PeerId>,
)

/**
 * Router-owned: how each pending sync is being asked for — attempt count, next attempt time,
 * and the devices that declined. What is missing and who may hold it is the orchestrator's
 * ([PendingSyncRepository]), under the same sync id.
 */
internal interface SyncAttemptStore {
    /** Starts tracking [syncId] (idempotent: a known sync keeps its state). */
    suspend fun start(syncId: Uuid, firstAttemptAt: Instant)

    /** Stops tracking [syncId]; its declined devices go with it. */
    suspend fun drop(syncId: Uuid)

    suspend fun earliestDueAt(): Instant?
    suspend fun findDue(now: Instant, limit: Int): List<SyncAttemptRow>

    /** Counts an attempt and schedules the next one. */
    suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Instant)

    /** Moves the next attempt to [nextAttemptAt] without counting one. */
    suspend fun postpone(syncId: Uuid, nextAttemptAt: Instant)

    /** Brings [syncIds] forward to [now]; never pushes one back. */
    suspend fun advanceToNow(syncIds: Collection<Uuid>, now: Instant)

    suspend fun declinedDevices(syncId: Uuid): Set<PeerId>
    suspend fun addDeclined(syncId: Uuid, deviceId: PeerId)
    suspend fun removeDeclined(syncId: Uuid, deviceIds: Collection<PeerId>)
}

internal class DefaultSyncAttemptStore(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : SyncAttemptStore {

    private val queries = database.syncAttemptQueries

    override suspend fun start(syncId: Uuid, firstAttemptAt: Instant) {
        withContext(dbDispatcher) { queries.insertSyncAttempt(syncId, firstAttemptAt) }
    }

    override suspend fun drop(syncId: Uuid) {
        withContext(dbDispatcher) { queries.deleteSyncAttempt(syncId) }
    }

    override suspend fun earliestDueAt(): Instant? =
        withContext(dbDispatcher) { queries.selectEarliestDueAt().executeAsOneOrNull()?.MIN }

    override suspend fun findDue(now: Instant, limit: Int): List<SyncAttemptRow> =
        withContext(dbDispatcher) {
            queries.selectDueSyncAttempts(now, limit.toLong()).executeAsList().map { row ->
                SyncAttemptRow(
                    syncId = row.sync_id,
                    attempts = row.attempts.toInt(),
                    declinedDevices = queries.selectDeclinedDevices(row.sync_id).executeAsList().toSet(),
                )
            }
        }

    override suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Instant) {
        withContext(dbDispatcher) { queries.recordAttempt(nextAttemptAt, syncId) }
    }

    override suspend fun postpone(syncId: Uuid, nextAttemptAt: Instant) {
        withContext(dbDispatcher) { queries.updateNextAttemptAt(nextAttemptAt, syncId) }
    }

    override suspend fun advanceToNow(syncIds: Collection<Uuid>, now: Instant) {
        if (syncIds.isEmpty()) return
        withContext(dbDispatcher) { queries.advanceToNow(now, syncIds) }
    }

    override suspend fun declinedDevices(syncId: Uuid): Set<PeerId> =
        withContext(dbDispatcher) { queries.selectDeclinedDevices(syncId).executeAsList().toSet() }

    override suspend fun addDeclined(syncId: Uuid, deviceId: PeerId) {
        withContext(dbDispatcher) { queries.insertDeclinedDevice(syncId, deviceId) }
    }

    override suspend fun removeDeclined(syncId: Uuid, deviceIds: Collection<PeerId>) {
        if (deviceIds.isEmpty()) return
        withContext(dbDispatcher) { queries.deleteDeclinedDevices(syncId, deviceIds) }
    }
}
