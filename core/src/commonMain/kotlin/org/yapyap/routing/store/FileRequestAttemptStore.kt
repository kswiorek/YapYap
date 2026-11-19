package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal data class FileRequestAttempt(
    val fileId: FileId,
    val attempts: Int,
    val declinedDevices: Set<PeerId>,
    /** ACK received, OFFER pending: the device asked and the wait deadline, else null. */
    val waitingDevice: PeerId?,
    val waitingUntil: Instant?,
    /** In-flight REQUEST's transferId for outcome correlation, else null. */
    val requestTransferId: TransferId?,
)

/**
 * Router-owned: how each pending file request is being asked for — attempt count, next
 * attempt time, the devices that declined, and the WAITING state. What is missing and who
 * may hold it is the orchestrator's ([org.yapyap.orchestrator.file.PendingFileRequestStore]),
 * under the same file id.
 */
internal interface FileRequestAttemptStore {
    /** Starts tracking [fileId] (idempotent: a known file keeps its state). */
    suspend fun start(fileId: FileId, firstAttemptAt: Instant)

    /** Stops tracking [fileId]; its declined devices go with it. */
    suspend fun drop(fileId: FileId)

    suspend fun earliestDueAt(): Instant?
    suspend fun findDue(now: Instant, limit: Int): List<FileRequestAttempt>

    suspend fun findById(fileId: FileId): FileRequestAttempt?
    suspend fun findByTransferId(transferId: TransferId): FileRequestAttempt?

    /** Counts an attempt and schedules the next one. */
    suspend fun recordAttempt(fileId: FileId, nextAttemptAt: Instant)

    /** Moves the next attempt to [nextAttemptAt] without counting one. */
    suspend fun postpone(fileId: FileId, nextAttemptAt: Instant)

    /** Brings [fileIds] forward to [now]; never pushes one back. */
    suspend fun advanceToNow(fileIds: Collection<FileId>, now: Instant)

    /** Enters WAITING for the OFFER from [device] until [until]. */
    suspend fun setWaiting(fileId: FileId, device: PeerId, until: Instant, requestTransferId: TransferId)

    /** Leaves WAITING (satisfied, timed out, or declined); clears the request ref too. */
    suspend fun clearWaiting(fileId: FileId)

    /** Records the in-flight REQUEST's transferId for outcome correlation. */
    suspend fun setRequestTransfer(fileId: FileId, transferId: TransferId)

    suspend fun declinedDevices(fileId: FileId): Set<PeerId>
    suspend fun addDeclined(fileId: FileId, device: PeerId)
    suspend fun removeDeclined(fileId: FileId, devices: Collection<PeerId>)

    /** Every tracked file id (link-up fan-out). */
    suspend fun allIds(): List<FileId>
}

internal class DefaultFileRequestAttemptStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileRequestAttemptStore {
    private val queries = database.fileRequestAttemptQueries

    override suspend fun start(fileId: FileId, firstAttemptAt: Instant) {
        withContext(dbDispatcher) { queries.insertFileRequestAttempt(fileId, firstAttemptAt) }
    }

    override suspend fun drop(fileId: FileId) {
        withContext(dbDispatcher) { queries.deleteFileRequestAttempt(fileId) }
    }

    override suspend fun earliestDueAt(): Instant? =
        withContext(dbDispatcher) { queries.selectEarliestDueFileRequestAt().executeAsOneOrNull()?.MIN }

    override suspend fun findDue(now: Instant, limit: Int): List<FileRequestAttempt> =
        withContext(dbDispatcher) {
            queries.selectDueFileRequestAttempts(now, limit.toLong()).executeAsList().map { row ->
                row.toAttempt(queries.selectDeclinedFileRequestDevices(row.file_id).executeAsList().toSet())
            }
        }

    override suspend fun findById(fileId: FileId): FileRequestAttempt? = withContext(dbDispatcher) {
        val row = queries.selectFileRequestById(fileId).executeAsOneOrNull() ?: return@withContext null
        row.toAttempt(queries.selectDeclinedFileRequestDevices(fileId).executeAsList().toSet())
    }

    override suspend fun findByTransferId(transferId: TransferId): FileRequestAttempt? =
        withContext(dbDispatcher) {
            queries.selectFileRequestByTransferId(transferId.value.toHexString()).executeAsOneOrNull()?.let { row ->
                row.toAttempt(queries.selectDeclinedFileRequestDevices(row.file_id).executeAsList().toSet())
            }
        }

    override suspend fun recordAttempt(fileId: FileId, nextAttemptAt: Instant) {
        withContext(dbDispatcher) { queries.recordFileRequestAttempt(nextAttemptAt, fileId) }
    }

    override suspend fun postpone(fileId: FileId, nextAttemptAt: Instant) {
        withContext(dbDispatcher) { queries.updateFileRequestNextAttemptAt(nextAttemptAt, fileId) }
    }

    override suspend fun advanceToNow(fileIds: Collection<FileId>, now: Instant) {
        if (fileIds.isEmpty()) return
        withContext(dbDispatcher) { queries.advanceFileRequestsToNow(now, fileIds) }
    }

    override suspend fun setWaiting(fileId: FileId, device: PeerId, until: Instant, requestTransferId: TransferId) {
        withContext(dbDispatcher) {
            queries.setFileRequestWaiting(device, until, requestTransferId.value.toHexString(), fileId)
        }
    }

    override suspend fun clearWaiting(fileId: FileId) {
        withContext(dbDispatcher) { queries.clearFileRequestWaiting(fileId) }
    }

    override suspend fun setRequestTransfer(fileId: FileId, transferId: TransferId) {
        withContext(dbDispatcher) { queries.setFileRequestTransfer(transferId.value.toHexString(), fileId) }
    }

    override suspend fun declinedDevices(fileId: FileId): Set<PeerId> =
        withContext(dbDispatcher) { queries.selectDeclinedFileRequestDevices(fileId).executeAsList().toSet() }

    override suspend fun addDeclined(fileId: FileId, device: PeerId) {
        withContext(dbDispatcher) { queries.insertDeclinedFileRequestDevice(fileId, device) }
    }

    override suspend fun removeDeclined(fileId: FileId, devices: Collection<PeerId>) {
        if (devices.isEmpty()) return
        withContext(dbDispatcher) { queries.deleteDeclinedFileRequestDevices(fileId, devices) }
    }

    override suspend fun allIds(): List<FileId> = withContext(dbDispatcher) {
        queries.selectAllFileRequestIds().executeAsList()
    }

    private fun org.yapyap.persistence.File_request_attempts.toAttempt(
        declined: Set<PeerId>,
    ): FileRequestAttempt = FileRequestAttempt(
        fileId = file_id,
        attempts = attempts.toInt(),
        declinedDevices = declined,
        waitingDevice = waiting_device,
        waitingUntil = waiting_until,
        requestTransferId = request_transfer_id?.let { TransferId(Uuid.parseHex(it)) },
    )
}
