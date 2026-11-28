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

/**
 * Routing-owned receiving side (docs/envelope-redesign.md §5.4–§5.5): the seals opened here, every
 * hop delivering chunks to us, and the ciphertext of the hops we relay. Written by the target side
 * and the relay; a relay hop's rows go with it.
 */
internal interface FileIncomingStore {
    suspend fun insertSeal(seal: IncomingSeal)
    suspend fun findSeal(sealId: ContentId): IncomingSeal?
    suspend fun sealsByFile(fileId: FileId): List<IncomingSeal>

    /** Inserts [transfer]; false when a row with its id already exists. */
    suspend fun insertTransfer(transfer: IncomingTransfer): Boolean
    suspend fun findTransfer(transferId: TransferId): IncomingTransfer?
    suspend fun transfersByFile(fileId: FileId): List<IncomingTransfer>
    suspend fun deleteTransfer(transferId: TransferId)

    /** Drops seals and the target's transfers past their expiry; relay hops end with their forward. */
    suspend fun pruneExpired(now: Instant)

    /**
     * Admits relay hop [transfer] with its [sealBytes] in one transaction: evicts relay hops, least
     * recently fed first, until the hop's reservation fits [budget], then inserts it. Nothing is
     * written when it can't fit.
     */
    suspend fun admitRelay(transfer: IncomingTransfer, sealBytes: ByteArray, budget: Long, now: Instant): AdmitResult
    suspend fun relayHops(): List<RelayHop>
    suspend fun relayHop(transferId: TransferId): RelayHop?
    suspend fun relaySealBytes(transferId: TransferId): ByteArray?

    /** Stores one chunk of relay hop [transferId]; null when the hop is gone (nothing written). */
    suspend fun storeRelayChunk(transferId: TransferId, index: Int, ciphertext: ByteArray, now: Instant): RelayCoverage?
    suspend fun relayChunk(transferId: TransferId, index: Int): ByteArray?
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

    override suspend fun insertTransfer(transfer: IncomingTransfer): Boolean =
        withContext(dbDispatcher) { insert(transfer) }

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

    override suspend fun admitRelay(
        transfer: IncomingTransfer,
        sealBytes: ByteArray,
        budget: Long,
        now: Instant,
    ): AdmitResult = withContext(dbDispatcher) {
        db.transactionWithResult {
            // Victims are chosen read-only first: a refused admission commits no writes.
            val needed = relayReservedBytes(transfer.shape, sealBytes.size)
            val hops = queries.selectFileRelayHops(::relayHopRow).executeAsList()
            var used = hops.sumOf { it.reservedBytes }
            val victims = mutableListOf<TransferId>()
            for (hop in hops) {
                if (used + needed <= budget) break
                victims += hop.transfer.transferId
                used -= hop.reservedBytes
            }
            if (used + needed > budget) return@transactionWithResult AdmitResult(false, emptyList())
            victims.forEach { queries.deleteFileIncomingTransfer(it) }
            insert(transfer)
            queries.insertFileRelaySeal(transfer.transferId, sealBytes, now)
            AdmitResult(true, victims)
        }
    }

    override suspend fun relayHops(): List<RelayHop> = withContext(dbDispatcher) {
        queries.selectFileRelayHops(::relayHopRow).executeAsList()
    }

    override suspend fun relayHop(transferId: TransferId): RelayHop? = withContext(dbDispatcher) {
        queries.selectFileRelayHop(transferId, ::relayHopRow).executeAsOneOrNull()
    }

    override suspend fun relaySealBytes(transferId: TransferId): ByteArray? = withContext(dbDispatcher) {
        queries.selectFileRelaySealBytes(transferId).executeAsOneOrNull()
    }

    override suspend fun storeRelayChunk(
        transferId: TransferId,
        index: Int,
        ciphertext: ByteArray,
        now: Instant,
    ): RelayCoverage? = withContext(dbDispatcher) {
        db.transactionWithResult {
            // The hop first: one evicted or dropped meanwhile writes nothing.
            val hop = queries.selectFileRelayHop(transferId, ::relayHopRow).executeAsOneOrNull()
                ?: return@transactionWithResult null
            queries.insertFileRelayChunk(transferId, index.toLong(), now, ciphertext)
            val stored = queries.selectFileRelayChunkIndices(transferId).executeAsList()
            // Indices come lowest first, so the prefix is where each one still equals its position.
            val contiguous = stored.withIndex().takeWhile { (position, index) -> index == position.toLong() }.count()
            RelayCoverage(
                highestContiguousIndex = contiguous - 1,
                isWhole = stored.size >= hop.transfer.shape.chunkCount
            )
        }
    }

    override suspend fun relayChunk(transferId: TransferId, index: Int): ByteArray? = withContext(dbDispatcher) {
        queries.selectFileRelayChunk(transferId, index.toLong()).executeAsOneOrNull()
    }

    private fun insert(transfer: IncomingTransfer): Boolean =
        queries.insertFileIncomingTransfer(
            transfer_id = transfer.transferId,
            seal_id = transfer.sealId,
            sender_device = transfer.sender,
            final_target = transfer.finalTarget,
            chunk_size = transfer.shape.chunkSizeBytes.toLong(),
            byte_from = transfer.shape.byteFrom,
            byte_to = transfer.shape.byteTo,
            expires_at = transfer.expiresAt,
        ).value > 0L

    private fun relayHopRow(
        transferId: TransferId,
        sealId: ContentId,
        sender: PeerId,
        finalTarget: PeerId,
        chunkSize: Long,
        byteFrom: Long,
        byteTo: Long,
        expiresAt: Instant,
        sealSize: Long?,
        storedChunks: Long,
        lastStoredAt: Instant,
    ) = RelayHop(
        transfer = IncomingTransfer(
            transferId, sealId, sender, finalTarget, TransferShape(chunkSize.toInt(), byteFrom, byteTo), expiresAt,
        ),
        sealSize = sealSize?.toInt() ?: 0,
        storedChunks = storedChunks.toInt(),
        lastStoredAt = lastStoredAt,
    )

    private fun File_incoming_seals.toSeal() =
        IncomingSeal(sealId = seal_id, origin = origin_device, seal = FileSeal.decode(seal), expiresAt = expires_at)

    private fun File_incoming_transfers.toTransfer() = IncomingTransfer(
        transferId = transfer_id,
        sealId = seal_id,
        sender = sender_device,
        finalTarget = final_target,
        shape = TransferShape(chunk_size.toInt(), byte_from, byte_to),
        expiresAt = expires_at,
    )
}
