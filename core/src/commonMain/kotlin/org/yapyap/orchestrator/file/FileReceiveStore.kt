package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.FileId
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Receive state, `fileId`-scoped (design §14): PENDING → IN_FLIGHT (`.part` reserved) →
 * READY (coverage complete, last block verified, renamed); IN_FLIGHT → STALLED on a block
 * mismatch below [OrchestratorConfig.fileHashRetryLimit][org.yapyap.orchestrator.OrchestratorConfig.fileHashRetryLimit], FAILED at the limit or on ENOSPC;
 * PENDING → DECLINED (no space, or a large file this device did not fetch — local,
 * re-openable); IN_FLIGHT → STALLED on the gap trigger; READY → EVICTED on quota LRU.
 */
public enum class FileReceiveStatus {
    PENDING,
    IN_FLIGHT,
    STALLED,
    READY,
    FAILED,
    DECLINED,
    /** Reserved: sender-side aborts delete the row instead of marking it. */
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
    val partPath: String,
    val finalPath: String,
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

    /** Receives not yet READY and not terminal (PENDING, IN_FLIGHT, STALLED). */
    suspend fun active(): List<FileReceiveRow>
}

/**
 * Owner of `file_receives` + `file_receive_chunks` (guide §4). Generated queries stay here. Only the
 * receive engine holds this interface; everyone else reads through [FileReceiveReader] and changes a
 * receive through the engine's [FileReceiveControl].
 */
internal interface FileReceiveStore : FileReceiveReader {
    suspend fun upsert(row: FileReceiveRow)
    suspend fun updateProgress(fileId: FileId, contiguousBytes: Long, mismatches: Int, status: FileReceiveStatus, now: Instant)
    suspend fun updateBlockList(fileId: FileId, blockSize: Int, blockHashes: List<ByteArray>, fileName: String?, mimeType: String?, now: Instant)
    suspend fun updateStatus(fileId: FileId, status: FileReceiveStatus, now: Instant)
    suspend fun updateOrigin(fileId: FileId, messageId: Uuid, now: Instant)
    suspend fun delete(fileId: FileId)
    suspend fun ready(): List<FileReceiveRow>

    suspend fun stageChunk(fileId: FileId, range: StagedRange)
    suspend fun stagedByFile(fileId: FileId): List<StagedRange>
    suspend fun stagedBytes(fileId: FileId): Long
    suspend fun deleteStagedByFile(fileId: FileId)
    suspend fun deleteStagedBelow(fileId: FileId, endExclusive: Long)
    suspend fun deleteStagedFrom(fileId: FileId, start: Long)
    suspend fun trimStagedRow(fileId: FileId, oldStart: Long, newStart: Long, newLength: Long, newPlaintext: ByteArray)
}

internal class DefaultFileReceiveStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileReceiveStore {
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
            part_path = row.partPath,
            final_path = row.finalPath,
            origin_message_id = row.originMessageId?.toHexString(),
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

    override suspend fun updateBlockList(
        fileId: FileId,
        blockSize: Int,
        blockHashes: List<ByteArray>,
        fileName: String?,
        mimeType: String?,
        now: Instant,
    ) {
        withContext(dbDispatcher) {
            queries.updateFileReceiveBlockList(blockSize.toLong(), encodeBlockHashes(blockHashes), fileName, mimeType, now, fileId)
        }
    }

    override suspend fun updateStatus(fileId: FileId, status: FileReceiveStatus, now: Instant) {
        withContext(dbDispatcher) {
            queries.updateFileReceiveStatus(status, now, fileId)
        }
    }

    override suspend fun updateOrigin(fileId: FileId, messageId: Uuid, now: Instant) {
        withContext(dbDispatcher) {
            queries.updateFileReceiveOrigin(messageId.toHexString(), now, fileId)
        }
    }

    override suspend fun delete(fileId: FileId) {
        withContext(dbDispatcher) {
            queries.deleteFileReceiveById(fileId)
        }
    }

    override suspend fun active(): List<FileReceiveRow> = withContext(dbDispatcher) {
        queries.selectActiveFileReceives().executeAsList().map { it.toRow() }
    }

    override suspend fun ready(): List<FileReceiveRow> = withContext(dbDispatcher) {
        queries.selectReadyFileReceives().executeAsList().map { it.toRow() }
    }

    override suspend fun stageChunk(fileId: FileId, range: StagedRange) {
        withContext(dbDispatcher) {
            queries.insertFileReceiveChunk(fileId, range.startOffset, range.length, range.plaintext)
        }
    }

    override suspend fun stagedByFile(fileId: FileId): List<StagedRange> = withContext(dbDispatcher) {
        queries.selectFileReceiveChunksByFile(fileId).executeAsList().map {
            StagedRange(it.start_offset, it.length, it.plaintext)
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

    override suspend fun deleteStagedFrom(fileId: FileId, start: Long) {
        withContext(dbDispatcher) {
            queries.deleteFileReceiveChunksFrom(fileId, start)
        }
    }

    override suspend fun trimStagedRow(
        fileId: FileId,
        oldStart: Long,
        newStart: Long,
        newLength: Long,
        newPlaintext: ByteArray,
    ) {
        withContext(dbDispatcher) {
            queries.updateFileReceiveChunk(newStart, newLength, newPlaintext, fileId, oldStart)
        }
    }

    private fun org.yapyap.persistence.File_receives.toRow(): FileReceiveRow = FileReceiveRow(
        fileId = file_id,
        status = status,
        totalBytes = total_bytes,
        contiguousBytes = contiguous_bytes,
        blockSize = block_size?.toInt(),
        blockHashes = block_hashes?.let { decodeBlockHashes(it) },
        fileName = file_name,
        mimeType = mime_type,
        hashMismatches = hash_mismatches.toInt(),
        partPath = part_path,
        finalPath = final_path,
        originMessageId = origin_message_id?.let { Uuid.parseHex(it) },
        createdAt = created_at,
        updatedAt = updated_at,
    )
}
