package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.File_incoming_seals
import org.yapyap.persistence.File_incoming_transfers
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileSeal
import org.yapyap.protocol.envelopes.TransferShape
import kotlin.time.Instant

/** A seal opened here: what [origin] sealed for us, kept until it expires. */
internal class IncomingSeal(
    val sealId: ContentId,
    val origin: PeerId,
    val seal: FileSeal,
    val expiresAt: Instant,
)

/** A hop delivering chunks of seal [sealId] to us from [sender]. */
internal data class IncomingTransfer(
    val transferId: TransferId,
    val sealId: ContentId,
    val sender: PeerId,
    val shape: TransferShape,
    val expiresAt: Instant,
)

/** Routing-owned `file_incoming_seals` and `file_incoming_transfers`: written only by the target side. */
internal interface FileIncomingStore {
    suspend fun insertSeal(seal: IncomingSeal)
    suspend fun findSeal(sealId: ContentId): IncomingSeal?
    suspend fun sealsByFile(fileId: FileId): List<IncomingSeal>

    /** Inserts [transfer]; false when a row with its id already exists. */
    suspend fun insertTransfer(transfer: IncomingTransfer): Boolean
    suspend fun findTransfer(transferId: TransferId): IncomingTransfer?
    suspend fun transfersByFile(fileId: FileId): List<IncomingTransfer>
    suspend fun deleteTransfer(transferId: TransferId)

    /** Drops seals and transfers past their expiry. */
    suspend fun pruneExpired(now: Instant)
}

internal class DefaultFileIncomingStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileIncomingStore {
    private val db = database
    private val queries = database.fileIncomingQueries

    override suspend fun insertSeal(seal: IncomingSeal) {
        withContext(dbDispatcher) {
            queries.insertFileIncomingSeal(
                seal.sealId,
                seal.origin,
                seal.seal.fileId,
                seal.seal.encode(),
                seal.expiresAt
            )
        }
    }

    override suspend fun findSeal(sealId: ContentId): IncomingSeal? = withContext(dbDispatcher) {
        queries.selectFileIncomingSeal(sealId).executeAsOneOrNull()?.toSeal()
    }

    override suspend fun sealsByFile(fileId: FileId): List<IncomingSeal> = withContext(dbDispatcher) {
        queries.selectFileIncomingSealsByFile(fileId).executeAsList().map { it.toSeal() }
    }

    override suspend fun insertTransfer(transfer: IncomingTransfer): Boolean = withContext(dbDispatcher) {
        queries.insertFileIncomingTransfer(
            transfer_id = transfer.transferId,
            seal_id = transfer.sealId,
            sender_device = transfer.sender,
            chunk_size = transfer.shape.chunkSizeBytes.toLong(),
            byte_from = transfer.shape.byteFrom,
            byte_to = transfer.shape.byteTo,
            expires_at = transfer.expiresAt,
        ).value > 0L
    }

    override suspend fun findTransfer(transferId: TransferId): IncomingTransfer? = withContext(dbDispatcher) {
        queries.selectFileIncomingTransfer(transferId).executeAsOneOrNull()?.toTransfer()
    }

    override suspend fun transfersByFile(fileId: FileId): List<IncomingTransfer> = withContext(dbDispatcher) {
        queries.selectFileIncomingTransfersByFile(fileId).executeAsList().map { it.toTransfer() }
    }

    override suspend fun deleteTransfer(transferId: TransferId) {
        withContext(dbDispatcher) { queries.deleteFileIncomingTransfer(transferId) }
    }

    override suspend fun pruneExpired(now: Instant) {
        withContext(dbDispatcher) {
            db.transaction {
                queries.deleteFileIncomingTransfersExpiredAt(now)
                queries.deleteFileIncomingSealsExpiredAt(now)
            }
        }
    }

    private fun File_incoming_seals.toSeal() =
        IncomingSeal(sealId = seal_id, origin = origin_device, seal = FileSeal.decode(seal), expiresAt = expires_at)

    private fun File_incoming_transfers.toTransfer() = IncomingTransfer(
        transferId = transfer_id,
        sealId = seal_id,
        sender = sender_device,
        shape = TransferShape(chunk_size.toInt(), byte_from, byte_to),
        expiresAt = expires_at,
    )
}
