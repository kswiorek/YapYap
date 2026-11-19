package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.FileId
import kotlin.time.Instant

/** Trigger state of one wanted file (design §9): PENDING until every candidate declines. */
public enum class PendingFileRequestStatus {
    PENDING,
    UNAVAILABLE,
}

internal data class PendingFileRequest(
    val fileId: FileId,
    val status: PendingFileRequestStatus,
    val nextAttemptAt: Instant,
    val updatedAt: Instant,
)

/**
 * Owner of `pending_file_requests` (guide §4). Generated `fileTransferQueries` stay here:
 * same owner (`orchestrator/file`) as the sibling `file_transfers` table.
 */
internal interface PendingFileRequestStore {
    /** Inserts or re-arms [fileId] as PENDING (a new message re-arms UNAVAILABLE). */
    suspend fun upsertPending(fileId: FileId, nextAttemptAt: Instant, now: Instant): Unit
    suspend fun find(fileId: FileId): PendingFileRequest?
    suspend fun setUnavailable(fileId: FileId, now: Instant): Unit
    suspend fun drop(fileId: FileId): Unit
    suspend fun due(now: Instant): List<PendingFileRequest>
}

internal class DefaultPendingFileRequestStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PendingFileRequestStore {
    private val queries = database.fileTransferQueries

    override suspend fun upsertPending(fileId: FileId, nextAttemptAt: Instant, now: Instant): Unit =
        withContext(dbDispatcher) {
            queries.insertPendingFileRequest(fileId, PendingFileRequestStatus.PENDING, nextAttemptAt, now)
        }

    override suspend fun find(fileId: FileId): PendingFileRequest? = withContext(dbDispatcher) {
        queries.selectPendingFileRequestById(fileId).executeAsOneOrNull()?.toRow()
    }

    override suspend fun setUnavailable(fileId: FileId, now: Instant): Unit = withContext(dbDispatcher) {
        queries.updatePendingFileRequestStatus(PendingFileRequestStatus.UNAVAILABLE, now, fileId)
    }

    override suspend fun drop(fileId: FileId): Unit = withContext(dbDispatcher) {
        queries.deletePendingFileRequest(fileId)
    }

    override suspend fun due(now: Instant): List<PendingFileRequest> = withContext(dbDispatcher) {
        queries.selectDuePendingFileRequests(now).executeAsList().map { it.toRow() }
    }

    private fun org.yapyap.persistence.Pending_file_requests.toRow(): PendingFileRequest = PendingFileRequest(
        fileId = file_id,
        status = status,
        nextAttemptAt = next_attempt_at,
        updatedAt = updated_at,
    )
}
