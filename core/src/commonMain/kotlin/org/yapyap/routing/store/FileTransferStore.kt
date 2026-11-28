package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.File_transfers_out
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.TransferShape
import kotlin.uuid.Uuid

/** Routing-owned `file_transfers_out` and `file_transfer_chunks`: written only by the transfer engine. */
internal interface FileTransferStore {
    suspend fun insert(transfer: OutgoingTransfer)
    suspend fun find(transferId: TransferId): OutgoingTransfer?
    suspend fun live(): List<OutgoingTransfer>
    suspend fun bySeal(sealId: ContentId): List<OutgoingTransfer>
    suspend fun bySourceTransfer(sourceTransferId: TransferId): List<OutgoingTransfer>
    suspend fun setStatus(transferId: TransferId, status: FileTransferStatus)
    suspend fun setOffer(transferId: TransferId, packetId: Uuid)
    suspend fun setProgress(transferId: TransferId, nextChunkIndex: Int, contiguousAcked: Int)

    /** Records that chunk [index] of [transferId] is queued as outbox packet [packetId]. */
    suspend fun recordChunk(transferId: TransferId, index: Int, packetId: Uuid)

    /** Queued chunk packets of [transferId] by index. */
    suspend fun chunks(transferId: TransferId): Map<Int, Uuid>

    /** The transfer and chunk index a queued packet carries, or null when it is not a chunk of ours. */
    suspend fun chunkByPacket(packetId: Uuid): Pair<TransferId, Int>?
    suspend fun deleteChunk(packetId: Uuid)
    suspend fun deleteChunks(transferId: TransferId)
    suspend fun delete(transferId: TransferId)
}

internal class DefaultFileTransferStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileTransferStore {
    private val queries = database.fileTransferQueries

    override suspend fun insert(transfer: OutgoingTransfer) {
        withContext(dbDispatcher) {
            queries.insertFileTransfer(
                transfer_id = transfer.transferId,
                seal_id = transfer.sealId,
                source_transfer_id = transfer.sourceTransferId,
                peer = transfer.peer,
                chunk_size = transfer.shape.chunkSizeBytes.toLong(),
                byte_from = transfer.shape.byteFrom,
                byte_to = transfer.shape.byteTo,
                next_chunk_index = transfer.nextChunkIndex.toLong(),
                contiguous_acked = transfer.contiguousAcked.toLong(),
                status = transfer.status.name,
                created_at = transfer.createdAt,
                expires_at = transfer.expiresAt,
            )
        }
    }

    override suspend fun find(transferId: TransferId): OutgoingTransfer? = withContext(dbDispatcher) {
        queries.selectFileTransfer(transferId).executeAsOneOrNull()?.toTransfer()
    }

    override suspend fun live(): List<OutgoingTransfer> = withContext(dbDispatcher) {
        queries.selectFileTransfersWithStatus(FileTransferStatus.entries.filter { it.isLive }.map { it.name })
            .executeAsList().map { it.toTransfer() }
    }

    override suspend fun bySeal(sealId: ContentId): List<OutgoingTransfer> = withContext(dbDispatcher) {
        queries.selectFileTransfersBySeal(sealId).executeAsList().map { it.toTransfer() }
    }

    override suspend fun bySourceTransfer(sourceTransferId: TransferId): List<OutgoingTransfer> =
        withContext(dbDispatcher) {
            queries.selectFileTransfersBySourceTransfer(sourceTransferId).executeAsList().map { it.toTransfer() }
        }

    override suspend fun setStatus(transferId: TransferId, status: FileTransferStatus) {
        withContext(dbDispatcher) { queries.updateFileTransferStatus(status.name, transferId) }
    }

    override suspend fun setOffer(transferId: TransferId, packetId: Uuid) {
        withContext(dbDispatcher) { queries.updateFileTransferOffer(packetId, transferId) }
    }

    override suspend fun setProgress(transferId: TransferId, nextChunkIndex: Int, contiguousAcked: Int) {
        withContext(dbDispatcher) {
            queries.updateFileTransferProgress(nextChunkIndex.toLong(), contiguousAcked.toLong(), transferId)
        }
    }

    override suspend fun recordChunk(transferId: TransferId, index: Int, packetId: Uuid) {
        withContext(dbDispatcher) { queries.insertFileTransferChunk(transferId, index.toLong(), packetId) }
    }

    override suspend fun chunks(transferId: TransferId): Map<Int, Uuid> = withContext(dbDispatcher) {
        queries.selectFileTransferChunks(transferId).executeAsList()
            .associate { it.chunk_index.toInt() to it.packet_id }
    }

    override suspend fun chunkByPacket(packetId: Uuid): Pair<TransferId, Int>? = withContext(dbDispatcher) {
        queries.selectFileTransferChunkByPacket(packetId).executeAsOneOrNull()
            ?.let { it.transfer_id to it.chunk_index.toInt() }
    }

    override suspend fun deleteChunk(packetId: Uuid) {
        withContext(dbDispatcher) { queries.deleteFileTransferChunkByPacket(packetId) }
    }

    override suspend fun deleteChunks(transferId: TransferId) {
        withContext(dbDispatcher) { queries.deleteFileTransferChunks(transferId) }
    }

    override suspend fun delete(transferId: TransferId) {
        withContext(dbDispatcher) { queries.deleteFileTransfer(transferId) }
    }

    private fun File_transfers_out.toTransfer() = OutgoingTransfer(
        transferId = transfer_id,
        sealId = seal_id,
        sourceTransferId = source_transfer_id,
        peer = peer,
        shape = TransferShape(chunk_size.toInt(), byte_from, byte_to),
        nextChunkIndex = next_chunk_index.toInt(),
        contiguousAcked = contiguous_acked.toInt(),
        offerPacketId = offer_packet_id,
        status = FileTransferStatus.valueOf(status),
        createdAt = created_at,
        expiresAt = expires_at,
    )
}
