package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.File_relay_transfers
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.TransferShape
import kotlin.time.Instant

/** Routing-owned transient ciphertext for other devices (docs/envelope-redesign.md §5.4): written
 * by the relay door (admission and chunks) and the forwards (dropping finished transfers). The
 * relay never holds a key.
 */
internal interface FileRelayStore {
    /**
     * Admission in one transaction: evicts whole transfers, least recently fed first, until
     * [transfer]'s reservation fits [budget], then inserts it. Nothing is written when it can't fit.
     */
    suspend fun admit(transfer: RelayTransfer, budget: Long): AdmitResult
    suspend fun find(transferId: TransferId): RelayTransfer?
    suspend fun all(): List<RelayTransfer>

    /**
     * Stores one chunk and promotes RESERVED → WHOLE when every chunk is there, in one
     * transaction. Returns the status afterwards, or null when the transfer is gone (nothing written).
     */
    suspend fun storeChunk(transferId: TransferId, index: Int, ciphertext: ByteArray, now: Instant): FileRelayStatus?

    /** Stored chunk indices, lowest first; no blobs. */
    suspend fun chunkIndices(transferId: TransferId): List<Int>
    suspend fun chunk(transferId: TransferId, index: Int): ByteArray?
    suspend fun delete(transferId: TransferId)
}

internal class DefaultFileRelayStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileRelayStore {
    private val db = database
    private val queries = database.fileRelayQueries

    override suspend fun admit(transfer: RelayTransfer, budget: Long): AdmitResult = withContext(dbDispatcher) {
        db.transactionWithResult {
            // Victims are chosen read-only first: a refused admission commits no writes.
            val used = queries.sumFileRelayReservedBytes().executeAsOne()
            val victims = mutableListOf<TransferId>()
            var remaining = used
            if (used + transfer.reservedBytes > budget) {
                for (candidate in queries.selectFileRelayEvictionOrder().executeAsList()) {
                    if (remaining + transfer.reservedBytes <= budget) break
                    victims += candidate.transfer_id
                    remaining -= candidate.reserved_bytes
                }
                if (remaining + transfer.reservedBytes > budget) {
                    return@transactionWithResult AdmitResult(false, emptyList())
                }
            }
            victims.forEach { queries.deleteFileRelayTransfer(it) }
            queries.insertFileRelayTransfer(
                transfer_id = transfer.transferId,
                seal_id = transfer.sealId,
                origin_device = transfer.origin,
                final_target = transfer.finalTarget,
                chunk_size = transfer.shape.chunkSizeBytes.toLong(),
                byte_from = transfer.shape.byteFrom,
                byte_to = transfer.shape.byteTo,
                seal_bytes = transfer.sealBytes,
                status = transfer.status.name,
                reserved_bytes = transfer.reservedBytes,
                created_at = transfer.createdAt,
                last_chunk_at = transfer.lastChunkAt,
                expires_at = transfer.expiresAt,
            )
            AdmitResult(true, victims)
        }
    }

    override suspend fun find(transferId: TransferId): RelayTransfer? = withContext(dbDispatcher) {
        queries.selectFileRelayTransfer(transferId).executeAsOneOrNull()?.toTransfer()
    }

    override suspend fun all(): List<RelayTransfer> = withContext(dbDispatcher) {
        queries.selectFileRelayTransfers().executeAsList().map { it.toTransfer() }
    }

    override suspend fun storeChunk(
        transferId: TransferId,
        index: Int,
        ciphertext: ByteArray,
        now: Instant,
    ): FileRelayStatus? = withContext(dbDispatcher) {
        db.transactionWithResult {
            // Parent first: a transfer evicted or dropped meanwhile writes nothing.
            val row = queries.selectFileRelayTransfer(transferId).executeAsOneOrNull()
                ?: return@transactionWithResult null
            queries.insertFileRelayChunk(transferId, index.toLong(), ciphertext)
            queries.touchFileRelayTransfer(now, transferId)
            val whole = queries.countFileRelayChunks(transferId).executeAsOne() >= row.toTransfer().shape.chunkCount
            if (row.status == FileRelayStatus.RESERVED.name && whole) {
                queries.updateFileRelayTransferStatus(FileRelayStatus.WHOLE.name, transferId)
                FileRelayStatus.WHOLE
            } else {
                FileRelayStatus.valueOf(row.status)
            }
        }
    }

    override suspend fun chunkIndices(transferId: TransferId): List<Int> = withContext(dbDispatcher) {
        queries.selectFileRelayChunkIndices(transferId).executeAsList().map { it.toInt() }
    }

    override suspend fun chunk(transferId: TransferId, index: Int): ByteArray? = withContext(dbDispatcher) {
        queries.selectFileRelayChunk(transferId, index.toLong()).executeAsOneOrNull()
    }

    override suspend fun delete(transferId: TransferId) {
        withContext(dbDispatcher) { queries.deleteFileRelayTransfer(transferId) }
    }

    private fun File_relay_transfers.toTransfer() = RelayTransfer(
        transferId = transfer_id,
        sealId = seal_id,
        origin = origin_device,
        finalTarget = final_target,
        shape = TransferShape(chunk_size.toInt(), byte_from, byte_to),
        sealBytes = seal_bytes,
        status = FileRelayStatus.valueOf(status),
        reservedBytes = reserved_bytes,
        createdAt = created_at,
        lastChunkAt = last_chunk_at,
        expiresAt = expires_at,
    )
}
