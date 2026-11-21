package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.File_incoming_transfers
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.TransferKey
import kotlin.time.Instant

/** Receiver-side transfer state: rows live until their transfer ends, then are dropped. */
internal data class FileIncomingTransfer(
    val transferId: TransferId,
    val fileId: FileId,
    val authorDevice: PeerId,
    val key: TransferKey,
    val chunkSizeBytes: Int,
    val totalBytes: Long,
    val startIndex: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as FileIncomingTransfer
        if (transferId != other.transferId) return false
        if (fileId != other.fileId) return false
        if (authorDevice != other.authorDevice) return false
        if (key != other.key) return false
        if (chunkSizeBytes != other.chunkSizeBytes) return false
        if (totalBytes != other.totalBytes) return false
        if (startIndex != other.startIndex) return false
        if (createdAt != other.createdAt) return false
        if (expiresAt != other.expiresAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + fileId.hashCode()
        result = 31 * result + authorDevice.hashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + chunkSizeBytes
        result = 31 * result + totalBytes.hashCode()
        result = 31 * result + startIndex
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}

/** Routing-owned: one row per incoming transfer (device grain, design §12). */
internal interface FileIncomingTransferStore {
    suspend fun insert(row: FileIncomingTransfer)
    suspend fun find(transferId: TransferId): FileIncomingTransfer?
    suspend fun findByFile(fileId: FileId): List<FileIncomingTransfer>
    suspend fun delete(transferId: TransferId)
    suspend fun deleteByFile(fileId: FileId)

    /** Removes rows expired at [now]; returns them. */
    suspend fun pruneExpired(now: Instant): List<FileIncomingTransfer>
}

internal class DefaultFileIncomingTransferStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileIncomingTransferStore {
    private val queries = database.fileIncomingQueries

    override suspend fun insert(row: FileIncomingTransfer) {
        withContext(dbDispatcher) {
            queries.insertFileIncomingTransfer(
                transfer_id = row.transferId,
                file_id = row.fileId,
                author_device = row.authorDevice,
                key = row.key,
                chunk_size_bytes = row.chunkSizeBytes.toLong(),
                total_bytes = row.totalBytes,
                start_index = row.startIndex.toLong(),
                created_at = row.createdAt,
                expires_at = row.expiresAt,
            )
        }
    }

    override suspend fun find(transferId: TransferId): FileIncomingTransfer? = withContext(dbDispatcher) {
        queries.selectFileIncomingTransferById(transferId).executeAsOneOrNull()?.toRow()
    }

    override suspend fun findByFile(fileId: FileId): List<FileIncomingTransfer> = withContext(dbDispatcher) {
        queries.selectFileIncomingTransfersByFileId(fileId).executeAsList().map { it.toRow() }
    }

    override suspend fun delete(transferId: TransferId) {
        withContext(dbDispatcher) {
            queries.deleteFileIncomingTransferById(transferId)
        }
    }

    override suspend fun deleteByFile(fileId: FileId) {
        withContext(dbDispatcher) {
            queries.deleteFileIncomingTransfersByFileId(fileId)
        }
    }

    override suspend fun pruneExpired(now: Instant): List<FileIncomingTransfer> = withContext(dbDispatcher) {
        val expired = queries.selectExpiredFileIncomingTransfers(now).executeAsList().map { it.toRow() }
        if (expired.isNotEmpty()) {
            queries.deleteExpiredFileIncomingTransfers(now)
        }
        expired
    }

    private fun File_incoming_transfers.toRow(): FileIncomingTransfer =
        FileIncomingTransfer(
            transferId = transfer_id,
            fileId = file_id,
            authorDevice = author_device,
            key = key,
            chunkSizeBytes = chunk_size_bytes.toInt(),
            totalBytes = total_bytes,
            startIndex = start_index.toInt(),
            createdAt = created_at,
            expiresAt = expires_at,
        )
}
