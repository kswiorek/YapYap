package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.routing.candidate.CandidateEntry
import org.yapyap.routing.candidate.CandidateLedger
import org.yapyap.routing.candidate.CandidateWaiting
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Router-owned: how each pending file pull is being asked for — attempt count, next
 * attempt time, the devices that declined, and the WAITING state. What is missing and who
 * may hold it is the orchestrator's ([org.yapyap.orchestrator.file.PendingFileRequestStore]),
 * under the same file id.
 */
internal interface FilePullAttemptStore {
    /** Starts tracking [fileId] (idempotent: a known file keeps its state). */
    suspend fun start(fileId: FileId, pullClass: FilePullClass, firstAttemptAt: Instant)

    /** Stops tracking [fileId]; its declined devices go with it. */
    suspend fun drop(fileId: FileId)

    /** Earliest next attempt among [pullClass]'s rows: each class's loop sleeps on its own. */
    suspend fun earliestDueAt(pullClass: FilePullClass): Instant?
    suspend fun findDue(now: Instant, limit: Int, pullClass: FilePullClass): List<FilePullAttempt>

    suspend fun findById(fileId: FileId): FilePullAttempt?
    suspend fun findByRequestPacketId(packetId: Uuid): FilePullAttempt?

    /** Counts an attempt and schedules the next one. */
    suspend fun recordAttempt(fileId: FileId, nextAttemptAt: Instant)

    /** Moves the next attempt to [nextAttemptAt] without counting one. */
    suspend fun postpone(fileId: FileId, nextAttemptAt: Instant)

    /** Brings [fileIds] forward to [now]; never pushes one back. */
    suspend fun advanceToNow(fileIds: Collection<FileId>, now: Instant)

    /** Enters WAITING for the OFFER from [device] until [until]. */
    suspend fun setWaiting(fileId: FileId, device: PeerId, until: Instant, requestPacketId: Uuid)

    /** Leaves WAITING (satisfied, timed out, or declined); clears the request ref too. */
    suspend fun clearWaiting(fileId: FileId)

    /** Records the in-flight REQUEST's packet id for outcome correlation. */
    suspend fun setRequestPacket(fileId: FileId, packetId: Uuid)

    suspend fun declinedDevices(fileId: FileId): Set<PeerId>
    suspend fun addDeclined(fileId: FileId, device: PeerId)
    suspend fun removeDeclined(fileId: FileId, devices: Collection<PeerId>)

    /** Every tracked file id of [pullClass] (link-up fan-out). */
    suspend fun idsOf(pullClass: FilePullClass): List<FileId>

    /**
     * The asking loop's row-state view for [pullClass]. The store owns the parent + declined-children
     * composition; the SMALL view exposes the persisted WAITING state, the LARGE one has none.
     */
    fun candidateLedger(pullClass: FilePullClass): CandidateLedger<FileId>
}

internal class DefaultFilePullAttemptStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FilePullAttemptStore {
    private val queries = database.filePullAttemptQueries

    override suspend fun start(fileId: FileId, pullClass: FilePullClass, firstAttemptAt: Instant) {
        withContext(dbDispatcher) { queries.insertFilePullAttempt(fileId, firstAttemptAt, pullClass.name) }
    }

    override suspend fun drop(fileId: FileId) {
        withContext(dbDispatcher) { queries.deleteFilePullAttempt(fileId) }
    }

    override suspend fun earliestDueAt(pullClass: FilePullClass): Instant? =
        withContext(dbDispatcher) {
            queries.selectEarliestDueFilePullAt(pullClass.name).executeAsOneOrNull()?.MIN
        }

    override suspend fun findDue(now: Instant, limit: Int, pullClass: FilePullClass): List<FilePullAttempt> =
        withContext(dbDispatcher) {
            queries.selectDueFilePullAttempts(now, pullClass.name, limit.toLong(), ::joinedRow).executeAsList()
                .withDeclined()
        }

    override suspend fun findById(fileId: FileId): FilePullAttempt? = withContext(dbDispatcher) {
        queries.selectFilePullById(fileId, ::joinedRow).executeAsList().withDeclined().singleOrNull()
    }

    override suspend fun findByRequestPacketId(packetId: Uuid): FilePullAttempt? = withContext(dbDispatcher) {
        queries.selectFilePullByRequestPacketId(packetId, ::joinedRow).executeAsList().withDeclined().singleOrNull()
    }

    override suspend fun recordAttempt(fileId: FileId, nextAttemptAt: Instant) {
        withContext(dbDispatcher) { queries.recordFilePullAttempt(nextAttemptAt, fileId) }
    }

    override suspend fun postpone(fileId: FileId, nextAttemptAt: Instant) {
        withContext(dbDispatcher) { queries.updateFilePullNextAttemptAt(nextAttemptAt, fileId) }
    }

    override suspend fun advanceToNow(fileIds: Collection<FileId>, now: Instant) {
        if (fileIds.isEmpty()) return
        withContext(dbDispatcher) { queries.advanceFilePullsToNow(now, fileIds) }
    }

    override suspend fun setWaiting(fileId: FileId, device: PeerId, until: Instant, requestPacketId: Uuid) {
        withContext(dbDispatcher) {
            queries.setFilePullWaiting(device, until, requestPacketId, fileId)
        }
    }

    override suspend fun clearWaiting(fileId: FileId) {
        withContext(dbDispatcher) { queries.clearFilePullWaiting(fileId) }
    }

    override suspend fun setRequestPacket(fileId: FileId, packetId: Uuid) {
        withContext(dbDispatcher) { queries.setFilePullRequestPacket(packetId, fileId) }
    }

    override suspend fun declinedDevices(fileId: FileId): Set<PeerId> =
        withContext(dbDispatcher) { queries.selectDeclinedFilePullDevices(fileId).executeAsList().toSet() }

    override suspend fun addDeclined(fileId: FileId, device: PeerId) {
        withContext(dbDispatcher) { queries.insertDeclinedFilePullDevice(fileId, device) }
    }

    override suspend fun removeDeclined(fileId: FileId, devices: Collection<PeerId>) {
        if (devices.isEmpty()) return
        withContext(dbDispatcher) { queries.deleteDeclinedFilePullDevices(fileId, devices) }
    }

    override suspend fun idsOf(pullClass: FilePullClass): List<FileId> = withContext(dbDispatcher) {
        queries.selectFilePullIdsByClass(pullClass.name).executeAsList()
    }

    override fun candidateLedger(pullClass: FilePullClass): CandidateLedger<FileId> =
        FilePullCandidateLedger(this, pullClass)

    /** One result row of a parent-with-declined-devices join: the parent, and one declined device or null. */
    private fun joinedRow(
        fileId: FileId,
        attempts: Long,
        waitingDevice: PeerId?,
        waitingUntil: Instant?,
        requestPacketId: Uuid?,
        pullClass: String,
        declinedDevice: PeerId?,
    ): Pair<FilePullAttempt, PeerId?> = FilePullAttempt(
        fileId = fileId,
        attempts = attempts.toInt(),
        declinedDevices = emptySet(),
        waitingDevice = waitingDevice,
        waitingUntil = waitingUntil,
        requestPacketId = requestPacketId,
        pullClass = FilePullClass.valueOf(pullClass),
    ) to declinedDevice

    /** Folds the join back into one attempt per file, in the query's order. */
    private fun List<Pair<FilePullAttempt, PeerId?>>.withDeclined(): List<FilePullAttempt> =
        groupBy { (attempt, _) -> attempt.fileId }.values.map { rows ->
            rows.first().first.copy(declinedDevices = rows.mapNotNull { it.second }.toSet())
        }
}

/**
 * One class's view over [FilePullAttemptStore]: the parent row plus its declined children, composed
 * by the store that owns the tables. The LARGE view has no WAITING (its answer and bytes share a
 * channel), so its WAITING methods are no-ops.
 */
private class FilePullCandidateLedger(
    private val store: FilePullAttemptStore,
    private val pullClass: FilePullClass,
) : CandidateLedger<FileId> {
    override suspend fun start(id: FileId, firstAttemptAt: Instant) =
        store.start(id, pullClass, firstAttemptAt)

    override suspend fun drop(id: FileId) = store.drop(id)
    override suspend fun earliestDueAt(): Instant? = store.earliestDueAt(pullClass)

    override suspend fun findDue(now: Instant, limit: Int): List<CandidateEntry<FileId>> =
        store.findDue(now, limit, pullClass).map { row ->
            val waiting = if (pullClass == FilePullClass.SMALL) row.waitingState() else null
            CandidateEntry(row.fileId, row.attempts, row.declinedDevices, waiting)
        }

    override suspend fun recordAttempt(id: FileId, nextAttemptAt: Instant) =
        store.recordAttempt(id, nextAttemptAt)

    override suspend fun postpone(id: FileId, nextAttemptAt: Instant) = store.postpone(id, nextAttemptAt)
    override suspend fun advanceToNow(ids: Collection<FileId>, now: Instant) = store.advanceToNow(ids, now)
    override suspend fun declinedDevices(id: FileId): Set<PeerId> = store.declinedDevices(id)
    override suspend fun addDeclined(id: FileId, device: PeerId) = store.addDeclined(id, device)
    override suspend fun removeDeclined(id: FileId, devices: Collection<PeerId>) =
        store.removeDeclined(id, devices)

    override suspend fun setWaiting(id: FileId, device: PeerId, until: Instant) {
        if (pullClass != FilePullClass.SMALL) return
        val requestPacketId = store.findById(id)?.requestPacketId ?: return
        store.setWaiting(id, device, until, requestPacketId)
    }

    override suspend fun clearWaiting(id: FileId) {
        if (pullClass == FilePullClass.SMALL) store.clearWaiting(id)
    }

    private fun FilePullAttempt.waitingState(): CandidateWaiting? {
        val device = waitingDevice ?: return null
        val until = waitingUntil ?: return null
        return CandidateWaiting(device, until)
    }
}
