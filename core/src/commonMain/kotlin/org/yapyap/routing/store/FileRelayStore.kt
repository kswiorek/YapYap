package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.File_relay_chunks
import org.yapyap.persistence.File_relay_transfers
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Relay-side lifecycle of one deposit (design §7.2): RESERVED (admitted, chunks
 * landing) → WHOLE (covered suffix complete) → FORWARDING (marker queued/sent) →
 * STREAMING (marker ACKed, chunks queued). Drained, declined, expired or evicted
 * rows are deleted — relays forget after delivery (invariant 3).
 */
internal enum class FileRelayStatus {
    RESERVED,
    WHOLE,
    FORWARDING,
    STREAMING,
}

/** One stored deposit: the OFFER blob verbatim plus the covered-suffix chunk blobs. */
internal data class FileRelayTransfer(
    val transferId: TransferId,
    val sourceDevice: PeerId,
    val targetDevice: PeerId,
    val chunkSizeBytes: Int,
    val totalBytes: Long,
    val startIndex: Int,
    /** The OFFER's inner FileEnvelope bytes verbatim (signature intact), replayed on forward. */
    val offerPayload: ByteArray,
    val status: FileRelayStatus,
    /** Covered-suffix bytes reserved against `RouterConfig.fileRelayBudgetBytes` at admission. */
    val reservedBytes: Long,
    val markerPacketId: Uuid?,
    val createdAt: Instant,
    val lastChunkAt: Instant,
    val expiresAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as FileRelayTransfer
        if (transferId != other.transferId) return false
        if (sourceDevice != other.sourceDevice) return false
        if (targetDevice != other.targetDevice) return false
        if (chunkSizeBytes != other.chunkSizeBytes) return false
        if (totalBytes != other.totalBytes) return false
        if (startIndex != other.startIndex) return false
        if (!offerPayload.contentEquals(other.offerPayload)) return false
        if (status != other.status) return false
        if (reservedBytes != other.reservedBytes) return false
        if (markerPacketId != other.markerPacketId) return false
        if (createdAt != other.createdAt) return false
        if (lastChunkAt != other.lastChunkAt) return false
        if (expiresAt != other.expiresAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + sourceDevice.hashCode()
        result = 31 * result + targetDevice.hashCode()
        result = 31 * result + chunkSizeBytes
        result = 31 * result + totalBytes.hashCode()
        result = 31 * result + startIndex
        result = 31 * result + offerPayload.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + reservedBytes.hashCode()
        result = 31 * result + markerPacketId.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + lastChunkAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}

/** A stored chunk without its blob: what the door's ACK and the forward's restream need. */
internal data class FileRelayChunkRef(val chunkIndex: Int, val packetId: Uuid?)

internal data class FileRelayChunk(
    val transferId: TransferId,
    val chunkIndex: Int,
    /** Outer packet id of the forwarded copy (null until the forward enqueues it). */
    val packetId: Uuid?,
    /** The CHUNK's inner FileEnvelope bytes as they arrived (signature intact). */
    val blob: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as FileRelayChunk
        if (transferId != other.transferId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (packetId != other.packetId) return false
        if (!blob.contentEquals(other.blob)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + packetId.hashCode()
        result = 31 * result + blob.contentHashCode()
        return result
    }
}

/**
 * Routing-owned transient ciphertext for foreign traffic (design §7, §13).
 * Ciphertext only — the relay never holds a key and never sees plaintext
 * (relay path stays routing-only; the orchestrator never learns of deposits).
 */
internal interface FileRelayStore {
    suspend fun insert(row: FileRelayTransfer)
    suspend fun find(transferId: TransferId): FileRelayTransfer?
    suspend fun live(): List<FileRelayTransfer>
    suspend fun setStatus(transferId: TransferId, status: FileRelayStatus)
    suspend fun setMarker(transferId: TransferId, markerPacketId: Uuid?)
    suspend fun touch(transferId: TransferId, now: Instant)
    suspend fun reservedBytes(): Long
    suspend fun delete(transferId: TransferId)

    /**
     * Admission in one transaction (design §7.2): evict oldest transfers until [reserveBytes]
     * fits in [budget], insert [row], return the evicted ids. All in one transaction.
     */
    suspend fun admit(row: FileRelayTransfer, reserveBytes: Long, budget: Long): AdmitResult

    /** Stored chunk indices and forward packet ids, oldest index first; no blobs. */
    suspend fun chunkRefs(transferId: TransferId): List<FileRelayChunkRef>

    /** One stored chunk's blob, or null when it is gone. */
    suspend fun chunkBlob(transferId: TransferId, chunkIndex: Int): ByteArray?
    suspend fun chunkCount(transferId: TransferId): Long
    suspend fun setChunkPacket(transferId: TransferId, chunkIndex: Int, packetId: Uuid)
    suspend fun findChunkByPacket(packetId: Uuid): FileRelayChunk?
    suspend fun deleteChunkByPacket(packetId: Uuid)

    /**
     * Stores one chunk and promotes RESERVED → WHOLE when the covered suffix is complete,
     * in one transaction. Returns the row's status afterwards, or null when the row is gone
     * (evicted or dropped meanwhile): then nothing is written.
     */
    suspend fun storeChunk(transferId: TransferId, chunkIndex: Int, blob: ByteArray, now: Instant): FileRelayStatus?

    /**
     * Promotes a RESERVED row to WHOLE when its covered suffix is complete, in one
     * transaction. Returns true when the row is WHOLE afterwards.
     */
    suspend fun promoteIfWhole(transferId: TransferId): Boolean
}

internal data class AdmitResult(val admitted: Boolean, val evicted: List<TransferId>)

internal class DefaultFileRelayStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileRelayStore {
    private val db = database
    private val queries = database.fileRelayQueries

    override suspend fun insert(row: FileRelayTransfer) {
        withContext(dbDispatcher) {
            insertRelayTransfer(row)
        }
    }

    override suspend fun find(transferId: TransferId): FileRelayTransfer? = withContext(dbDispatcher) {
        queries.selectFileRelayTransferById(transferId).executeAsOneOrNull()?.toRow()
    }

    override suspend fun live(): List<FileRelayTransfer> = withContext(dbDispatcher) {
        queries.selectLiveFileRelayTransfers().executeAsList().map { it.toRow() }
    }

    override suspend fun setStatus(transferId: TransferId, status: FileRelayStatus) {
        withContext(dbDispatcher) {
            queries.updateFileRelayTransferStatus(status.name, transferId)
        }
    }

    override suspend fun setMarker(transferId: TransferId, markerPacketId: Uuid?) {
        withContext(dbDispatcher) {
            queries.updateFileRelayTransferMarker(
                marker_packet_id = markerPacketId,
                status = FileRelayStatus.FORWARDING.name,
                transfer_id = transferId,
            )
        }
    }

    override suspend fun touch(transferId: TransferId, now: Instant) {
        withContext(dbDispatcher) {
            queries.touchFileRelayTransfer(now, transferId)
        }
    }

    override suspend fun reservedBytes(): Long = withContext(dbDispatcher) {
        queries.sumFileRelayReservedBytes().executeAsOneOrNull() ?: 0L
    }

    override suspend fun delete(transferId: TransferId) {
        withContext(dbDispatcher) {
            queries.deleteFileRelayTransferById(transferId)
        }
    }

    override suspend fun admit(row: FileRelayTransfer, reserveBytes: Long, budget: Long): AdmitResult =
        withContext(dbDispatcher) {
            // One transaction: victims are chosen read-only first, and nothing is deleted
            // unless the row will actually fit — a refused admission commits no writes.
            db.transactionWithResult {
                val used = queries.sumFileRelayReservedBytes().executeAsOneOrNull() ?: 0L
                if (used + reserveBytes > budget) {
                    // Oldest first over every row, until the new one fits.
                    val victims = mutableListOf<TransferId>()
                    var remaining = used
                    for (candidate in queries.selectFileRelayEvictionOrder().executeAsList()) {
                        if (remaining + reserveBytes <= budget) break
                        val id = candidate.transfer_id
                        if (id == row.transferId) continue
                        victims += id
                        remaining -= candidate.reserved_bytes
                    }
                    if (remaining + reserveBytes > budget) {
                        return@transactionWithResult AdmitResult(false, emptyList())
                    }
                    for (victim in victims) {
                        queries.deleteFileRelayTransferById(victim)
                    }
                    insertRelayTransfer(row)
                    AdmitResult(true, victims)
                } else {
                    insertRelayTransfer(row)
                    AdmitResult(true, emptyList())
                }
            }
        }

    private fun insertRelayTransfer(row: FileRelayTransfer) {
        queries.insertFileRelayTransfer(
            transfer_id = row.transferId,
            source_device = row.sourceDevice,
            target_device = row.targetDevice,
            chunk_size_bytes = row.chunkSizeBytes.toLong(),
            total_bytes = row.totalBytes,
            start_index = row.startIndex.toLong(),
            offer_payload = row.offerPayload,
            status = row.status.name,
            reserved_bytes = row.reservedBytes,
            marker_packet_id = row.markerPacketId,
            created_at = row.createdAt,
            last_chunk_at = row.lastChunkAt,
            expires_at = row.expiresAt,
        )
    }

    override suspend fun storeChunk(
        transferId: TransferId,
        chunkIndex: Int,
        blob: ByteArray,
        now: Instant,
    ): FileRelayStatus? = withContext(dbDispatcher) {
        db.transactionWithResult {
            // Parent first: a row evicted or dropped meanwhile writes nothing (and never trips
            // the chunk table's foreign key).
            queries.selectFileRelayTransferById(transferId)
                .executeAsOneOrNull() ?: return@transactionWithResult null
            queries.insertFileRelayChunk(transferId, chunkIndex.toLong(), null, blob)
            queries.touchFileRelayTransfer(now, transferId)
            val row = queries.selectFileRelayTransferById(transferId).executeAsOne()
            if (row.status == FileRelayStatus.RESERVED.name && suffixComplete(row)) {
                queries.updateFileRelayTransferStatus(FileRelayStatus.WHOLE.name, transferId)
                FileRelayStatus.WHOLE
            } else {
                FileRelayStatus.valueOf(row.status)
            }
        }
    }

    override suspend fun promoteIfWhole(transferId: TransferId): Boolean = withContext(dbDispatcher) {
        db.transactionWithResult {
            val row = queries.selectFileRelayTransferById(transferId)
                .executeAsOneOrNull() ?: return@transactionWithResult false
            if (row.status == FileRelayStatus.WHOLE.name) return@transactionWithResult true
            if (row.status == FileRelayStatus.RESERVED.name && suffixComplete(row)) {
                queries.updateFileRelayTransferStatus(FileRelayStatus.WHOLE.name, transferId)
                true
            } else {
                false
            }
        }
    }

    /** Whether every index of the row's covered suffix is stored. */
    private fun suffixComplete(row: File_relay_transfers): Boolean {
        val count = if (row.total_bytes == 0L) 0 else ((row.total_bytes - 1) / row.chunk_size_bytes + 1).toInt()
        val expected = maxOf(0, count - row.start_index.toInt())
        return queries.countFileRelayChunksByTransfer(row.transfer_id).executeAsOne() >= expected
    }

    override suspend fun chunkRefs(transferId: TransferId): List<FileRelayChunkRef> = withContext(dbDispatcher) {
        queries.selectFileRelayChunkRefsByTransfer(transferId).executeAsList().map { ref ->
            FileRelayChunkRef(ref.chunk_index.toInt(), ref.packet_id)
        }
    }

    override suspend fun chunkBlob(transferId: TransferId, chunkIndex: Int): ByteArray? = withContext(dbDispatcher) {
        queries.selectFileRelayChunkBlob(transferId, chunkIndex.toLong()).executeAsOneOrNull()
    }

    override suspend fun chunkCount(transferId: TransferId): Long = withContext(dbDispatcher) {
        queries.countFileRelayChunksByTransfer(transferId).executeAsOne()
    }

    override suspend fun setChunkPacket(transferId: TransferId, chunkIndex: Int, packetId: Uuid) {
        withContext(dbDispatcher) {
            queries.updateFileRelayChunkPacketId(
                packetId,
                transferId,
                chunkIndex.toLong(),
            )
        }
    }

    override suspend fun findChunkByPacket(packetId: Uuid): FileRelayChunk? = withContext(dbDispatcher) {
        queries.selectFileRelayChunkByPacketId(packetId).executeAsOneOrNull()?.toRow()
    }

    override suspend fun deleteChunkByPacket(packetId: Uuid) {
        withContext(dbDispatcher) {
            queries.deleteFileRelayChunkByPacketId(packetId)
        }
    }

    private fun File_relay_transfers.toRow(): FileRelayTransfer = FileRelayTransfer(
        transferId = transfer_id,
        sourceDevice = source_device,
        targetDevice = target_device,
        chunkSizeBytes = chunk_size_bytes.toInt(),
        totalBytes = total_bytes,
        startIndex = start_index.toInt(),
        offerPayload = offer_payload,
        status = FileRelayStatus.valueOf(status),
        reservedBytes = reserved_bytes,
        markerPacketId = marker_packet_id,
        createdAt = created_at,
        lastChunkAt = last_chunk_at,
        expiresAt = expires_at,
    )

    private fun File_relay_chunks.toRow(): FileRelayChunk = FileRelayChunk(
        transferId = transfer_id,
        chunkIndex = chunk_index.toInt(),
        packetId = packet_id,
        blob = blob,
    )
}
