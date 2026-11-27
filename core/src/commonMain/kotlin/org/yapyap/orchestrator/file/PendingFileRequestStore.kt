package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.Pending_file_requests
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
    val updatedAt: Instant,
)

/**
 * Owner of `pending_file_requests` (guide §4): the user-facing trigger state only. When an ask
 * runs is routing's (`file_pull_attempts`); this row never schedules.
 */
internal interface PendingFileRequestStore {
    /** Inserts or re-arms [fileId] as PENDING (a new message re-arms UNAVAILABLE). */
    suspend fun upsertPending(fileId: FileId, now: Instant)
    suspend fun find(fileId: FileId): PendingFileRequest?
    suspend fun setUnavailable(fileId: FileId, now: Instant)
    suspend fun drop(fileId: FileId)

    /** Every PENDING row: the coordinator re-arms them each pass; routing owns their schedule. */
    suspend fun pending(): List<PendingFileRequest>
}

internal class DefaultPendingFileRequestStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PendingFileRequestStore {
    private val queries = database.pendingFileRequestQueries

    override suspend fun upsertPending(fileId: FileId, now: Instant) {
        withContext(dbDispatcher) {
            queries.insertPendingFileRequest(fileId, PendingFileRequestStatus.PENDING, now)
        }
    }

    override suspend fun find(fileId: FileId): PendingFileRequest? = withContext(dbDispatcher) {
        queries.selectPendingFileRequestById(fileId).executeAsOneOrNull()?.toRow()
    }

    override suspend fun setUnavailable(fileId: FileId, now: Instant) {
        withContext(dbDispatcher) {
            queries.updatePendingFileRequestStatus(PendingFileRequestStatus.UNAVAILABLE, now, fileId)
        }
    }

    override suspend fun drop(fileId: FileId) {
        withContext(dbDispatcher) {
            queries.deletePendingFileRequest(fileId)
        }
    }

    override suspend fun pending(): List<PendingFileRequest> = withContext(dbDispatcher) {
        queries.selectPendingFileRequests().executeAsList().map { it.toRow() }
    }

    private fun Pending_file_requests.toRow(): PendingFileRequest = PendingFileRequest(
        fileId = file_id,
        status = status,
        updatedAt = updated_at,
    )
}
