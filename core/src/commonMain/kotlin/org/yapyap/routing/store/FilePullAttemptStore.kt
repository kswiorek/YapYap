package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.File_pull_attempts
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
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
    suspend fun findByAttemptId(attemptId: Uuid): FilePullAttempt?

    /** Counts an attempt and schedules the next one. */
    suspend fun recordAttempt(fileId: FileId, nextAttemptAt: Instant)

    /** Moves the next attempt to [nextAttemptAt] without counting one. */
    suspend fun postpone(fileId: FileId, nextAttemptAt: Instant)

    /** Brings [fileIds] forward to [now]; never pushes one back. */
    suspend fun advanceToNow(fileIds: Collection<FileId>, now: Instant)

    /** Enters WAITING for the OFFER from [device] until [until]. */
    suspend fun setWaiting(fileId: FileId, device: PeerId, until: Instant, attemptId: Uuid)

    /** Leaves WAITING (satisfied, timed out, or declined); clears the request ref too. */
    suspend fun clearWaiting(fileId: FileId)

    /** Records the in-flight REQUEST's attempt id for outcome correlation. */
    suspend fun setAttempt(fileId: FileId, attemptId: Uuid)

    suspend fun declinedDevices(fileId: FileId): Set<PeerId>
    suspend fun addDeclined(fileId: FileId, device: PeerId)
    suspend fun removeDeclined(fileId: FileId, devices: Collection<PeerId>)

    /** Every tracked file id of [pullClass] (link-up fan-out). */
    suspend fun idsOf(pullClass: FilePullClass): List<FileId>
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
            queries.selectDueFilePullAttempts(now, pullClass.name, limit.toLong()).executeAsList().map { row ->
                row.toAttempt(queries.selectDeclinedFilePullDevices(row.file_id).executeAsList().toSet())
            }
        }

    override suspend fun findById(fileId: FileId): FilePullAttempt? = withContext(dbDispatcher) {
        val row = queries.selectFilePullById(fileId).executeAsOneOrNull() ?: return@withContext null
        row.toAttempt(queries.selectDeclinedFilePullDevices(fileId).executeAsList().toSet())
    }

    override suspend fun findByAttemptId(attemptId: Uuid): FilePullAttempt? =
        withContext(dbDispatcher) {
            queries.selectFilePullByAttemptId(attemptId).executeAsOneOrNull()?.let { row ->
                row.toAttempt(queries.selectDeclinedFilePullDevices(row.file_id).executeAsList().toSet())
            }
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

    override suspend fun setWaiting(fileId: FileId, device: PeerId, until: Instant, attemptId: Uuid) {
        withContext(dbDispatcher) {
            queries.setFilePullWaiting(device, until, attemptId, fileId)
        }
    }

    override suspend fun clearWaiting(fileId: FileId) {
        withContext(dbDispatcher) { queries.clearFilePullWaiting(fileId) }
    }

    override suspend fun setAttempt(fileId: FileId, attemptId: Uuid) {
        withContext(dbDispatcher) { queries.setFilePullAttempt(attemptId, fileId) }
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

    private fun File_pull_attempts.toAttempt(
        declined: Set<PeerId>,
    ): FilePullAttempt = FilePullAttempt(
        fileId = file_id,
        attempts = attempts.toInt(),
        declinedDevices = declined,
        waitingDevice = waiting_device,
        waitingUntil = waiting_until,
        attemptId = request_attempt_id,
        pullClass = FilePullClass.valueOf(pull_class),
    )
}
