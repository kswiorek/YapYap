package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.File_seals
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SealKey
import kotlin.time.Instant

/** Why a seal exists: our own send, or a serve answering a REQUEST. */
internal enum class FileSealKind {
    PUSH,
    SERVE,
}

/**
 * A seal's delivery state at its origin (docs/envelope-redesign.md §5.6). SENDING and HANDED_OFF
 * are live; the rest are final.
 */
internal enum class FileSealStatus {
    /** Transfers are still carrying it. */
    SENDING,

    /** The target's prefix plus one relay's whole remainder cover the file: relays deliver. */
    HANDED_OFF,

    /** The target holds the file: its COMPLETE arrived, or the direct transfer finished. */
    DELIVERED,

    /** The target refused the file. */
    DECLINED,

    /** Every transfer ended without delivery or hand-off; the pull path recovers. */
    ENDED,

    CANCELLED,
    EXPIRED;

    val isLive: Boolean get() = this == SENDING || this == HANDED_OFF
}

/** One file sealed for one target device at its origin (us). */
internal class OutgoingSeal(
    val sealId: ContentId,
    val fileId: FileId,
    val targetDevice: PeerId,
    val kind: FileSealKind,
    /** The seal's ratchet frame, attached unchanged to every transfer of it. */
    val sealBytes: ByteArray,
    val key: SealKey,
    val totalBytes: Long,
    val status: FileSealStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** Routing-owned `file_seals`: written only by the seal registry. */
internal interface FileSealStore {
    suspend fun insert(seal: OutgoingSeal)
    suspend fun find(sealId: ContentId): OutgoingSeal?
    suspend fun byFile(fileId: FileId): List<OutgoingSeal>
    suspend fun byFileAndTarget(fileId: FileId, target: PeerId): List<OutgoingSeal>
    suspend fun live(): List<OutgoingSeal>
    suspend fun expiredAt(now: Instant): List<OutgoingSeal>
    suspend fun setStatus(sealId: ContentId, status: FileSealStatus)

    /** Claims the seal's one deposit round: true exactly once per seal, across restarts. */
    suspend fun claimDeposit(sealId: ContentId, now: Instant): Boolean
    suspend fun delete(sealId: ContentId)
}

internal class DefaultFileSealStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileSealStore {
    private val queries = database.fileSealQueries

    override suspend fun insert(seal: OutgoingSeal) {
        withContext(dbDispatcher) {
            queries.insertFileSeal(
                seal_id = seal.sealId,
                file_id = seal.fileId,
                target_device = seal.targetDevice,
                kind = seal.kind.name,
                seal_bytes = seal.sealBytes,
                seal_key = seal.key,
                total_bytes = seal.totalBytes,
                status = seal.status.name,
                created_at = seal.createdAt,
                expires_at = seal.expiresAt,
            )
        }
    }

    override suspend fun find(sealId: ContentId): OutgoingSeal? = withContext(dbDispatcher) {
        queries.selectFileSeal(sealId).executeAsOneOrNull()?.toSeal()
    }

    override suspend fun byFile(fileId: FileId): List<OutgoingSeal> = withContext(dbDispatcher) {
        queries.selectFileSealsByFile(fileId).executeAsList().map { it.toSeal() }
    }

    override suspend fun byFileAndTarget(fileId: FileId, target: PeerId): List<OutgoingSeal> =
        withContext(dbDispatcher) {
            queries.selectFileSealsByFileAndTarget(fileId, target).executeAsList().map { it.toSeal() }
        }

    override suspend fun live(): List<OutgoingSeal> = withContext(dbDispatcher) {
        queries.selectFileSealsWithStatus(FileSealStatus.entries.filter { it.isLive }.map { it.name })
            .executeAsList().map { it.toSeal() }
    }

    override suspend fun expiredAt(now: Instant): List<OutgoingSeal> = withContext(dbDispatcher) {
        queries.selectFileSealsExpiredAt(now).executeAsList().map { it.toSeal() }
    }

    override suspend fun setStatus(sealId: ContentId, status: FileSealStatus) {
        withContext(dbDispatcher) { queries.updateFileSealStatus(status.name, sealId) }
    }

    override suspend fun claimDeposit(sealId: ContentId, now: Instant): Boolean = withContext(dbDispatcher) {
        queries.claimFileSealDeposit(now, sealId).value > 0L
    }

    override suspend fun delete(sealId: ContentId) {
        withContext(dbDispatcher) { queries.deleteFileSeal(sealId) }
    }

    private fun File_seals.toSeal() = OutgoingSeal(
        sealId = seal_id,
        fileId = file_id,
        targetDevice = target_device,
        kind = FileSealKind.valueOf(kind),
        sealBytes = seal_bytes,
        key = seal_key,
        totalBytes = total_bytes,
        status = FileSealStatus.valueOf(status),
        createdAt = created_at,
        expiresAt = expires_at,
    )
}
