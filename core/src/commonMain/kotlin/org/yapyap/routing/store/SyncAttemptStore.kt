package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.routing.candidate.CandidateAttemptStore
import org.yapyap.routing.candidate.CandidateRow
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal data class SyncAttempt(
    val syncId: Uuid,
    val attempts: Int,
    val declinedDevices: Set<PeerId>,
)

/**
 * Router-owned: how each pending sync is being asked for — attempt count, next attempt time,
 * and the devices that declined. What is missing and who may hold it is the orchestrator's
 * pending sync store, under the same sync id.
 */
internal interface SyncAttemptStore {
    /** Starts tracking [syncId] (idempotent: a known sync keeps its state). */
    suspend fun start(syncId: Uuid, firstAttemptAt: Instant)

    /** Stops tracking [syncId]; its declined devices go with it. */
    suspend fun drop(syncId: Uuid)

    suspend fun earliestDueAt(): Instant?
    suspend fun findDue(now: Instant, limit: Int): List<SyncAttempt>

    /** Counts an attempt and schedules the next one. */
    suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Instant)

    /** Moves the next attempt to [nextAttemptAt] without counting one. */
    suspend fun postpone(syncId: Uuid, nextAttemptAt: Instant)

    /** Brings [syncIds] forward to [now]; never pushes one back. */
    suspend fun advanceToNow(syncIds: Collection<Uuid>, now: Instant)

    suspend fun declinedDevices(syncId: Uuid): Set<PeerId>
    suspend fun addDeclined(syncId: Uuid, deviceId: PeerId)
    suspend fun removeDeclined(syncId: Uuid, deviceIds: Collection<PeerId>)

    /** The asking loop's row-state view, composed by the store that owns the tables. */
    fun candidateStore(): CandidateAttemptStore<Uuid>
}

internal class DefaultSyncAttemptStore(
    database: YapYapDatabase,
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

    override suspend fun findDue(now: Instant, limit: Int): List<SyncAttempt> =
        withContext(dbDispatcher) {
            // One statement: each due row joined with its declined devices, folded back per sync.
            queries.selectDueSyncAttempts(now, limit.toLong()).executeAsList()
                .groupBy { it.sync_id }.values.map { rows ->
                    SyncAttempt(
                        syncId = rows.first().sync_id,
                        attempts = rows.first().attempts.toInt(),
                        declinedDevices = rows.mapNotNull { it.declined_device }.toSet(),
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

    override fun candidateStore(): CandidateAttemptStore<Uuid> = SyncAttemptView(this)
}

/**
 * The sync asking loop's view over [SyncAttemptStore]: the parent row plus its declined children.
 * The sync path has no WAITING (an ACK means the reply is on its way), so those methods are no-ops.
 */
private class SyncAttemptView(
    private val store: SyncAttemptStore,
) : CandidateAttemptStore<Uuid> {
    override suspend fun start(id: Uuid, firstAttemptAt: Instant) = store.start(id, firstAttemptAt)
    override suspend fun drop(id: Uuid) = store.drop(id)
    override suspend fun earliestDueAt(): Instant? = store.earliestDueAt()
    override suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<Uuid>> =
        store.findDue(now, limit).map { CandidateRow(it.syncId, it.attempts, it.declinedDevices) }

    override suspend fun recordAttempt(id: Uuid, nextAttemptAt: Instant) =
        store.recordAttempt(id, nextAttemptAt)

    override suspend fun postpone(id: Uuid, nextAttemptAt: Instant) = store.postpone(id, nextAttemptAt)
    override suspend fun advanceToNow(ids: Collection<Uuid>, now: Instant) = store.advanceToNow(ids, now)
    override suspend fun declinedDevices(id: Uuid): Set<PeerId> = store.declinedDevices(id)
    override suspend fun addDeclined(id: Uuid, device: PeerId) = store.addDeclined(id, device)
    override suspend fun removeDeclined(id: Uuid, devices: Collection<PeerId>) =
        store.removeDeclined(id, devices)

    override suspend fun setWaiting(id: Uuid, device: PeerId, until: Instant) = Unit
    override suspend fun clearWaiting(id: Uuid) = Unit
}
