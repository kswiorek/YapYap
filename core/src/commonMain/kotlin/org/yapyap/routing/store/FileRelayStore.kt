package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
    STREAMING;

    val isLive: Boolean get() = true
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
        result = 31 * result + (markerPacketId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + lastChunkAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}

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
        result = 31 * result + (packetId?.hashCode() ?: 0)
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
    suspend fun setStatus(transferId: TransferId, status: FileRelayStatus, now: Instant)
    suspend fun setMarker(transferId: TransferId, markerPacketId: Uuid?)
    suspend fun touch(transferId: TransferId, now: Instant)
    suspend fun reservedBytes(): Long
    suspend fun evictionCandidates(limit: Long): List<TransferId>
    suspend fun expired(now: Instant, idleBefore: Instant): List<FileRelayTransfer>
    suspend fun delete(transferId: TransferId)
    suspend fun insertChunk(transferId: TransferId, chunkIndex: Int, blob: ByteArray)
    suspend fun chunks(transferId: TransferId): List<FileRelayChunk>
    suspend fun chunkCount(transferId: TransferId): Long
    suspend fun setChunkPacket(transferId: TransferId, chunkIndex: Int, packetId: Uuid)
    suspend fun findChunkByPacket(packetId: Uuid): FileRelayChunk?
    suspend fun deleteChunkByPacket(packetId: Uuid)
}

internal class DefaultFileRelayStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileRelayStore {
    private val queries = database.fileRelayQueries

    override suspend fun insert(row: FileRelayTransfer) {
        withContext(dbDispatcher) {
            queries.insertFileRelayTransfer(
                transfer_id = row.transferId.value.toHexString(),
                source_device = row.sourceDevice,
                target_device = row.targetDevice,
                chunk_size_bytes = row.chunkSizeBytes.toLong(),
                total_bytes = row.totalBytes,
                start_index = row.startIndex.toLong(),
                offer_payload = row.offerPayload,
                status = row.status.name,
                reserved_bytes = row.reservedBytes,
                marker_packet_id = row.markerPacketId?.toHexString(),
                created_at = row.createdAt,
                last_chunk_at = row.lastChunkAt,
                expires_at = row.expiresAt,
            )
        }
    }

    override suspend fun find(transferId: TransferId): FileRelayTransfer? = withContext(dbDispatcher) {
        queries.selectFileRelayTransferById(transferId.value.toHexString()).executeAsOneOrNull()?.toRow()
    }

    override suspend fun live(): List<FileRelayTransfer> = withContext(dbDispatcher) {
        queries.selectLiveFileRelayTransfers().executeAsList().map { it.toRow() }
    }

    override suspend fun setStatus(transferId: TransferId, status: FileRelayStatus, now: Instant) {
        withContext(dbDispatcher) {
            queries.updateFileRelayTransferStatus(status.name, now, transferId.value.toHexString())
        }
    }

    override suspend fun setMarker(transferId: TransferId, markerPacketId: Uuid?) {
        withContext(dbDispatcher) {
            queries.updateFileRelayTransferMarker(
                marker_packet_id = markerPacketId?.toHexString(),
                status = FileRelayStatus.FORWARDING.name,
                transfer_id = transferId.value.toHexString(),
            )
        }
    }

    override suspend fun touch(transferId: TransferId, now: Instant) {
        withContext(dbDispatcher) {
            queries.touchFileRelayTransfer(now, transferId.value.toHexString())
        }
    }

    override suspend fun reservedBytes(): Long = withContext(dbDispatcher) {
        queries.sumFileRelayReservedBytes().executeAsOneOrNull() ?: 0L
    }

    override suspend fun evictionCandidates(limit: Long): List<TransferId> = withContext(dbDispatcher) {
        queries.selectFileRelayEvictionCandidates(limit).executeAsList()
            .map { TransferId(Uuid.parseHex(it)) }
    }

    override suspend fun expired(now: Instant, idleBefore: Instant): List<FileRelayTransfer> =
        withContext(dbDispatcher) {
            queries.selectExpiredFileRelayTransfers(now, idleBefore).executeAsList().map { it.toRow() }
        }

    override suspend fun delete(transferId: TransferId) {
        withContext(dbDispatcher) {
            queries.deleteFileRelayTransferById(transferId.value.toHexString())
        }
    }

    override suspend fun insertChunk(transferId: TransferId, chunkIndex: Int, blob: ByteArray) {
        withContext(dbDispatcher) {
            queries.insertFileRelayChunk(transferId.value.toHexString(), chunkIndex.toLong(), null, blob)
        }
    }

    override suspend fun chunks(transferId: TransferId): List<FileRelayChunk> = withContext(dbDispatcher) {
        queries.selectFileRelayChunksByTransfer(transferId.value.toHexString()).executeAsList().map { it.toRow() }
    }

    override suspend fun chunkCount(transferId: TransferId): Long = withContext(dbDispatcher) {
        queries.countFileRelayChunksByTransfer(transferId.value.toHexString()).executeAsOne()
    }

    override suspend fun setChunkPacket(transferId: TransferId, chunkIndex: Int, packetId: Uuid) {
        withContext(dbDispatcher) {
            queries.updateFileRelayChunkPacketId(
                packetId.toHexString(),
                transferId.value.toHexString(),
                chunkIndex.toLong(),
            )
        }
    }

    override suspend fun findChunkByPacket(packetId: Uuid): FileRelayChunk? = withContext(dbDispatcher) {
        queries.selectFileRelayChunkByPacketId(packetId.toHexString()).executeAsOneOrNull()?.toRow()
    }

    override suspend fun deleteChunkByPacket(packetId: Uuid) {
        withContext(dbDispatcher) {
            queries.deleteFileRelayChunkByPacketId(packetId.toHexString())
        }
    }

    private fun org.yapyap.persistence.File_relay_transfers.toRow(): FileRelayTransfer = FileRelayTransfer(
        transferId = TransferId(Uuid.parseHex(transfer_id)),
        sourceDevice = source_device,
        targetDevice = target_device,
        chunkSizeBytes = chunk_size_bytes.toInt(),
        totalBytes = total_bytes,
        startIndex = start_index.toInt(),
        offerPayload = offer_payload,
        status = FileRelayStatus.valueOf(status),
        reservedBytes = reserved_bytes,
        markerPacketId = marker_packet_id?.let { Uuid.parseHex(it) },
        createdAt = created_at,
        lastChunkAt = last_chunk_at,
        expiresAt = expires_at,
    )

    private fun org.yapyap.persistence.File_relay_chunks.toRow(): FileRelayChunk = FileRelayChunk(
        transferId = TransferId(Uuid.parseHex(transfer_id)),
        chunkIndex = chunk_index.toInt(),
        packetId = packet_id?.let { Uuid.parseHex(it) },
        blob = blob,
    )
}
