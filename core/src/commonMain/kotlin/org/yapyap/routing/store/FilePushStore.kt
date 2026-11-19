package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal enum class FilePushKind {
    DIRECT,
    SERVE,

    /** Sender-side remainder deposit at one relay (outer target, §6.4): silent, never a refusal. */
    DEPOSIT,
}

internal enum class FilePushStatus {
    CREATED,
    IN_FLIGHT,
    COMPLETED,
    ENDED,
    EXPIRED,
    DECLINED,
    CANCELLED;

    val isLive: Boolean get() = this == CREATED || this == IN_FLIGHT
    val isTerminal: Boolean get() = !isLive
}

/**
 * Pusher-side per-device transfer state (design §13): per-depot resume points
 * (`nextChunkIndex`, `contiguousAcked`, `markerPacketId`), restart-recoverable from
 * these rows plus the outbox. Kind/status are plain strings mapped here.
 */
internal data class FilePush(
    val transferId: TransferId,
    val fileId: FileId,
    val targetDevice: PeerId,
    /**
     * Outer envelope target override: null for DIRECT/SERVE (outer == target),
     * the relay for DEPOSIT rows (outer == relay, inner stays the ultimate target).
     */
    val viaRelay: PeerId? = null,
    val kind: FilePushKind,
    val totalBytes: Long,
    val key: ByteArray,
    val chunkSizeBytes: Int,
    val startIndex: Int,
    val nextChunkIndex: Int,
    val contiguousAcked: Int,
    val markerPacketId: Uuid?,
    val status: FilePushStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as FilePush
        if (transferId != other.transferId) return false
        if (fileId != other.fileId) return false
        if (targetDevice != other.targetDevice) return false
        if (viaRelay != other.viaRelay) return false
        if (kind != other.kind) return false
        if (totalBytes != other.totalBytes) return false
        if (!key.contentEquals(other.key)) return false
        if (chunkSizeBytes != other.chunkSizeBytes) return false
        if (startIndex != other.startIndex) return false
        if (nextChunkIndex != other.nextChunkIndex) return false
        if (contiguousAcked != other.contiguousAcked) return false
        if (markerPacketId != other.markerPacketId) return false
        if (status != other.status) return false
        if (createdAt != other.createdAt) return false
        if (expiresAt != other.expiresAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + fileId.hashCode()
        result = 31 * result + targetDevice.hashCode()
        result = 31 * result + (viaRelay?.hashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + totalBytes.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + chunkSizeBytes
        result = 31 * result + startIndex
        result = 31 * result + nextChunkIndex
        result = 31 * result + contiguousAcked
        result = 31 * result + (markerPacketId?.hashCode() ?: 0)
        result = 31 * result + status.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}

/** Routing-owned: the pusher's per-device transfer state (design §12). */
internal interface FilePushStore {
    suspend fun insert(row: FilePush)
    suspend fun find(transferId: TransferId): FilePush?
    suspend fun findLiveByFileAndTarget(fileId: FileId, target: PeerId): List<FilePush>
    suspend fun live(): List<FilePush>

    /** The row whose OFFER marker is the outbox packet [markerPacketId] (backup trigger). */
    suspend fun findByMarkerPacket(markerPacketId: Uuid): FilePush?
    suspend fun setStatus(transferId: TransferId, status: FilePushStatus)
    suspend fun setMarker(transferId: TransferId, markerPacketId: Uuid?)
    suspend fun setProgress(transferId: TransferId, nextChunkIndex: Int, contiguousAcked: Int)
    suspend fun expired(now: Instant): List<FilePush>
}

internal class DefaultFilePushStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FilePushStore {
    private val queries = database.filePushQueries

    override suspend fun insert(row: FilePush) {
        withContext(dbDispatcher) {
            queries.insertFilePush(
                transfer_id = row.transferId.value.toHexString(),
                file_id = row.fileId,
                target_device = row.targetDevice,
                via_relay = row.viaRelay,
                kind = row.kind.name,
                total_bytes = row.totalBytes,
                key = row.key,
                chunk_size_bytes = row.chunkSizeBytes.toLong(),
                start_index = row.startIndex.toLong(),
                next_chunk_index = row.nextChunkIndex.toLong(),
                contiguous_acked = row.contiguousAcked.toLong(),
                marker_packet_id = row.markerPacketId?.toHexString(),
                status = row.status.name,
                created_at = row.createdAt,
                expires_at = row.expiresAt,
            )
        }
    }

    override suspend fun find(transferId: TransferId): FilePush? = withContext(dbDispatcher) {
        queries.selectFilePushById(transferId.value.toHexString()).executeAsOneOrNull()?.toRow()
    }

    override suspend fun findLiveByFileAndTarget(fileId: FileId, target: PeerId): List<FilePush> =
        withContext(dbDispatcher) {
            queries.selectLiveFilePushesByFileAndTarget(fileId, target).executeAsList().map { it.toRow() }
        }

    override suspend fun live(): List<FilePush> = withContext(dbDispatcher) {
        queries.selectLiveFilePushes().executeAsList().map { it.toRow() }
    }

    override suspend fun findByMarkerPacket(markerPacketId: Uuid): FilePush? = withContext(dbDispatcher) {
        queries.selectFilePushByMarkerPacketId(markerPacketId.toHexString()).executeAsOneOrNull()?.toRow()
    }

    override suspend fun setStatus(transferId: TransferId, status: FilePushStatus) {
        withContext(dbDispatcher) {
            queries.updateFilePushStatus(status.name, transferId.value.toHexString())
        }
    }

    override suspend fun setMarker(transferId: TransferId, markerPacketId: Uuid?) {
        withContext(dbDispatcher) {
            queries.updateFilePushMarker(markerPacketId?.toHexString(), transferId.value.toHexString())
        }
    }

    override suspend fun setProgress(transferId: TransferId, nextChunkIndex: Int, contiguousAcked: Int) {
        withContext(dbDispatcher) {
            queries.updateFilePushProgress(nextChunkIndex.toLong(), contiguousAcked.toLong(), transferId.value.toHexString())
        }
    }

    override suspend fun expired(now: Instant): List<FilePush> = withContext(dbDispatcher) {
        queries.selectExpiredFilePushes(now).executeAsList().map { it.toRow() }
    }

    private fun org.yapyap.persistence.File_pushes.toRow(): FilePush = FilePush(
        transferId = TransferId(Uuid.parseHex(transfer_id)),
        fileId = file_id,
        targetDevice = target_device,
        viaRelay = via_relay,
        kind = FilePushKind.valueOf(kind),
        totalBytes = total_bytes,
        key = key,
        chunkSizeBytes = chunk_size_bytes.toInt(),
        startIndex = start_index.toInt(),
        nextChunkIndex = next_chunk_index.toInt(),
        contiguousAcked = contiguous_acked.toInt(),
        markerPacketId = marker_packet_id?.let { Uuid.parseHex(it) },
        status = FilePushStatus.valueOf(status),
        createdAt = created_at,
        expiresAt = expires_at,
    )
}
