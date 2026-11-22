package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.receive.StagedSpan
import org.yapyap.persistence.File_receives
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.FileId
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Receive state, `fileId`-scoped (design §14): an accepted OFFER starts IN_FLIGHT (`.part`
 * reserved) → READY (coverage complete, last block verified, renamed); IN_FLIGHT → STALLED on a
 * block mismatch below [OrchestratorConfig.fileHashRetryLimit] or on the gap trigger, FAILED at
 * the limit; STALLED → IN_FLIGHT when a source resumes; DECLINED when there is no space or a
 * large file this device did not fetch (local, re-openable); READY → EVICTED on quota LRU.
 */
public enum class FileReceiveStatus {
    IN_FLIGHT,
    STALLED,
    READY,
    FAILED,
    DECLINED,

    /** Reserved: sender-side aborts the row instead of marking it. */
    CANCELLED,
    EVICTED,
}

internal data class FileReceiveRow(
    val fileId: FileId,
    val status: FileReceiveStatus,
    val totalBytes: Long,
    /** P: the coverage checkpoint; every block below P is verified. */
    val contiguousBytes: Long,
    val blockSize: Int?,
    val blockHashes: List<ByteArray>?,
    val fileName: String?,
    val mimeType: String?,
    val hashMismatches: Int,
    val originMessageId: Uuid?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class StagedRange(
    val startOffset: Long,
    val length: Long,
    val plaintext: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as StagedRange
        if (startOffset != other.startOffset) return false
        if (length != other.length) return false
        if (!plaintext.contentEquals(other.plaintext)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = startOffset.hashCode()
        result = 31 * result + length.hashCode()
        result = 31 * result + plaintext.contentHashCode()
        return result
    }
}

/** Read-only view of `file_receives`, for everyone but its writer, the receive engine. */
internal interface FileReceiveReader {
    suspend fun find(fileId: FileId): FileReceiveRow?

    /** Receives not yet READY and not terminal (IN_FLIGHT, STALLED). */
    suspend fun active(): List<FileReceiveRow>

    /** Every receive row, whatever its status (retention sweep). */
    suspend fun all(): List<FileReceiveRow>
}

/**
 * Owner of `file_receives` + `file_receive_chunks` (guide §4). Generated queries stay here. Only the
 * receive engine holds this interface; everyone else reads through [FileReceiveReader] and changes a
 * receive through the engine's [FileReceiveControl].
 */
internal interface FileReceiveStore : FileReceiveReader {
    suspend fun upsert(row: FileReceiveRow)
    suspend fun updateProgress(fileId: FileId, contiguousBytes: Long, mismatches: Int, status: FileReceiveStatus, now: Instant)
    suspend fun updateStatus(fileId: FileId, status: FileReceiveStatus, now: Instant)
    suspend fun updateOrigin(fileId: FileId, messageId: Uuid, now: Instant)
    suspend fun ready(): List<FileReceiveRow>


    /** Staged rows overlapping `[from, until)`: the blobs a drain needs, nothing beyond the gap. */
    suspend fun stagedOverlapping(fileId: FileId, from: Long, until: Long): List<StagedRange>

    /** Offsets and lengths only, no blobs — what coverage decisions run on. */
    suspend fun stagedRefsByFile(fileId: FileId): List<StagedSpan>
    suspend fun stagedBytes(fileId: FileId): Long
    suspend fun deleteStagedByFile(fileId: FileId)
    suspend fun deleteStagedBelow(fileId: FileId, endExclusive: Long)

    /** Stage [ranges] and move to [status] in one transaction: P moves before anything is deleted. */
    suspend fun stageChunks(fileId: FileId, ranges: List<StagedRange>, status: FileReceiveStatus, now: Instant)

    /**
     * Atomic checkpoint commit (design §8): P advances and the drained rows (every row ending at
     * or below [newP]) are deleted, in one transaction.
     */
    suspend fun commitDrain(fileId: FileId, newP: Long, mismatches: Int, status: FileReceiveStatus, now: Instant)

    /**
     * Atomic mismatch commit: P rewinds to [blockStart] and every staged row is dropped
     * (every remaining row either ends past the bad block's start or is a drained prefix
     * below the new P — a straddling row is trimmed to its prefix, then dropped as drained).
     */
    suspend fun commitMismatch(
        fileId: FileId,
        blockStart: Long,
        mismatches: Int,
        status: FileReceiveStatus,
        now: Instant,
    )

    /** Atomic READY commit: staged rows are dropped and the row goes READY in one transaction. */
    suspend fun markReady(fileId: FileId, newP: Long, mismatches: Int, now: Instant)

    /** Atomic delete of a receive and its staged rows. */
    suspend fun deleteWithStaged(fileId: FileId)
}

internal class DefaultFileReceiveStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileReceiveStore {
    private val db = database
    private val queries = database.fileQueries

    override suspend fun upsert(row: FileReceiveRow): Unit = withContext(dbDispatcher) {
        queries.insertFileReceive(
            file_id = row.fileId,
            status = row.status,
            total_bytes = row.totalBytes,
            contiguous_bytes = row.contiguousBytes,
            block_size = row.blockSize?.toLong(),
            block_hashes = row.blockHashes?.let { encodeBlockHashes(it) },
            file_name = row.fileName,
            mime_type = row.mimeType,
            hash_mismatches = row.hashMismatches.toLong(),
            origin_message_id = row.originMessageId,
            created_at = row.createdAt,
            updated_at = row.updatedAt,
        )
    }

    override suspend fun find(fileId: FileId): FileReceiveRow? = withContext(dbDispatcher) {
        queries.selectFileReceiveById(fileId).executeAsOneOrNull()?.toRow()
    }

    override suspend fun updateProgress(
        fileId: FileId,
        contiguousBytes: Long,
        mismatches: Int,
        status: FileReceiveStatus,
        now: Instant,
    ) {
        withContext(dbDispatcher) {
            queries.updateFileReceiveProgress(contiguousBytes, mismatches.toLong(), status, now, fileId)
        }
    }

    override suspend fun updateStatus(fileId: FileId, status: FileReceiveStatus, now: Instant) {
        withContext(dbDispatcher) {
            queries.updateFileReceiveStatus(status, now, fileId)
        }
    }

    override suspend fun updateOrigin(fileId: FileId, messageId: Uuid, now: Instant) {
        withContext(dbDispatcher) {
            queries.updateFileReceiveOrigin(messageId, now, fileId)
        }
    }

    override suspend fun active(): List<FileReceiveRow> = withContext(dbDispatcher) {
        queries.selectActiveFileReceives().executeAsList().map { it.toRow() }
    }

    override suspend fun ready(): List<FileReceiveRow> = withContext(dbDispatcher) {
        queries.selectReadyFileReceives().executeAsList().map { it.toRow() }
    }

    override suspend fun all(): List<FileReceiveRow> = withContext(dbDispatcher) {
        queries.selectAllFileReceives().executeAsList().map { it.toRow() }
    }

    override suspend fun stagedOverlapping(fileId: FileId, from: Long, until: Long): List<StagedRange> =
        withContext(dbDispatcher) {
            queries.selectFileReceiveChunksOverlapping(fileId, until, from).executeAsList().map {
                StagedRange(it.start_offset, it.length, it.plaintext)
            }
        }

    override suspend fun stagedRefsByFile(fileId: FileId): List<StagedSpan> = withContext(dbDispatcher) {
        queries.selectFileReceiveChunkRefsByFile(fileId).executeAsList().map {
            StagedSpan(it.start_offset, it.length)
        }
    }

    override suspend fun stagedBytes(fileId: FileId): Long = withContext(dbDispatcher) {
        queries.sumFileReceiveStagedBytes(fileId).executeAsOneOrNull() ?: 0L
    }

    override suspend fun deleteStagedByFile(fileId: FileId) {
        withContext(dbDispatcher) {
            queries.deleteFileReceiveChunksByFile(fileId)
        }
    }

    override suspend fun deleteStagedBelow(fileId: FileId, endExclusive: Long) {
        withContext(dbDispatcher) {
            queries.deleteFileReceiveChunksBelow(fileId, endExclusive)
        }
    }

    override suspend fun stageChunks(
        fileId: FileId,
        ranges: List<StagedRange>,
        status: FileReceiveStatus,
        now: Instant
    ) {
        withContext(dbDispatcher) {
            db.transaction {
                for (range in ranges) {
                    queries.insertFileReceiveChunk(fileId, range.startOffset, range.length, range.plaintext)
                }
                queries.updateFileReceiveStatus(status, now, fileId)
            }
        }
    }

    override suspend fun commitDrain(
        fileId: FileId,
        newP: Long,
        mismatches: Int,
        status: FileReceiveStatus,
        now: Instant,
    ) {
        withContext(dbDispatcher) {
            db.transaction {
                // P moves before anything is deleted.
                queries.updateFileReceiveProgress(newP, mismatches.toLong(), status, now, fileId)
                queries.deleteFileReceiveChunksBelow(fileId, newP)
            }
        }
    }

    override suspend fun commitMismatch(
        fileId: FileId,
        blockStart: Long,
        mismatches: Int,
        status: FileReceiveStatus,
        now: Instant,
    ) {
        withContext(dbDispatcher) {
            db.transaction {
                queries.updateFileReceiveProgress(blockStart, mismatches.toLong(), status, now, fileId)
                queries.deleteFileReceiveChunksByFile(fileId)
            }
        }
    }

    override suspend fun markReady(fileId: FileId, newP: Long, mismatches: Int, now: Instant) {
        withContext(dbDispatcher) {
            db.transaction {
                queries.updateFileReceiveProgress(newP, mismatches.toLong(), FileReceiveStatus.READY, now, fileId)
                queries.deleteFileReceiveChunksByFile(fileId)
            }
        }
    }

    override suspend fun deleteWithStaged(fileId: FileId) {
        withContext(dbDispatcher) {
            db.transaction {
                queries.deleteFileReceiveChunksByFile(fileId)
                queries.deleteFileReceiveById(fileId)
            }
        }
    }

    private fun File_receives.toRow(): FileReceiveRow = FileReceiveRow(
        fileId = file_id,
        status = status,
        totalBytes = total_bytes,
        contiguousBytes = contiguous_bytes,
        blockSize = block_size?.toInt(),
        blockHashes = block_hashes?.let { decodeBlockHashes(it) },
        fileName = file_name,
        mimeType = mime_type,
        hashMismatches = hash_mismatches.toInt(),
        originMessageId = origin_message_id,
        createdAt = created_at,
        updatedAt = updated_at,
    )
}
