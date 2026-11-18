package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import kotlin.time.Instant

public enum class FileTransferDirection {
    SEND,
    SERVE,
}

public enum class FileTransferClass {
    SMALL,
    LARGE,
}

public enum class FileTransferStatus {
    CREATED,
    IN_FLIGHT,
    COMPLETED,
    ENDED,
    EXPIRED,
    DECLINED,
    CANCELLED,
    FAILED,
}

/**
 * Our sends and serves: one row per (file, peer account) (design §13). The pusher owns
 * the per-device machinery; this row is the account-level send record the GUI and the
 * pull path reason about. COMPLETED transitions ride on the serve/request machinery
 * (Phase D); until then a row lands IN_FLIGHT when the send fans out.
 */
internal data class FileTransfer(
    val fileId: FileId,
    val peerAccountId: AccountId,
    val direction: FileTransferDirection,
    val klass: FileTransferClass,
    val status: FileTransferStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
)

internal interface FileTransferStore {
    suspend fun upsert(row: FileTransfer)
    suspend fun find(fileId: FileId, peerAccountId: AccountId): FileTransfer?
    suspend fun findByFile(fileId: FileId): List<FileTransfer>
    suspend fun setStatus(fileId: FileId, peerAccountId: AccountId, status: FileTransferStatus, now: Instant)
}

internal class DefaultFileTransferStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileTransferStore {
    private val queries = database.fileTransferQueries

    override suspend fun upsert(row: FileTransfer) {
        withContext(dbDispatcher) {
            queries.insertFileTransfer(
                file_id = row.fileId,
                peer_account_id = row.peerAccountId,
                direction = row.direction,
                transfer_class = row.klass,
                status = row.status,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
                expires_at = row.expiresAt,
            )
        }
    }

    override suspend fun find(fileId: FileId, peerAccountId: AccountId): FileTransfer? = withContext(dbDispatcher) {
        queries.selectFileTransfer(fileId, peerAccountId).executeAsOneOrNull()?.toRow()
    }

    override suspend fun findByFile(fileId: FileId): List<FileTransfer> = withContext(dbDispatcher) {
        queries.selectFileTransfersByFile(fileId).executeAsList().map { it.toRow() }
    }

    override suspend fun setStatus(fileId: FileId, peerAccountId: AccountId, status: FileTransferStatus, now: Instant) {
        withContext(dbDispatcher) {
            queries.updateFileTransferStatus(status, now, fileId, peerAccountId)
        }
    }

    private fun org.yapyap.persistence.File_transfers.toRow(): FileTransfer = FileTransfer(
        fileId = file_id,
        peerAccountId = peer_account_id,
        direction = direction,
        klass = transfer_class,
        status = status,
        createdAt = created_at,
        updatedAt = updated_at,
        expiresAt = expires_at,
    )
}
