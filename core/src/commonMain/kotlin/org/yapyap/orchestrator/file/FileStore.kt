package org.yapyap.orchestrator.file

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.io.AppendHandle
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.file.io.PositionedHandle
import org.yapyap.protocol.FileId
import org.yapyap.protocol.blockSizeFor
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Outcome of [FileStore.import]. */
internal sealed interface ImportResult {
    /** [created] is true when this import made a new descriptor (the send may release it). */
    data class Imported(val descriptor: FileDescriptor, val created: Boolean) : ImportResult

    /** The source is missing, unreadable, or ended before its size. */
    data object SourceUnreadable : ImportResult

    /** Not enough free space in the store directory for the copy. */
    data object NoSpace : ImportResult
}

/**
 * Permanent attachment store (design §10).
 *
 * - COPY: bytes live under the store dir as `<fileId hex>`, hashed in the same pass as the
 *   copy. Survives the original being deleted. Quota-managed (`FileQuota`) with true LRU
 *   over copies only, by `last_access_at`.
 * - REFERENCE: the original stays in place and is hashed in place, typed as PATH or
 *   CONTENT_URI (never sniffed). Outside the quota, never deleted by retention. A moved,
 *   deleted or edited original is caught by the block check while serving and marked
 *   SOURCE_LOST.
 */
internal interface FileStore {
    /** [onBytes] reports each run of bytes hashed, for progress. */
    suspend fun import(
        source: FileImportSource,
        fileName: String,
        mimeType: String?,
        keepCopy: Boolean?,
        onBytes: (Long) -> Unit = {},
    ): ImportResult

    suspend fun markSourceLost(fileId: FileId)

    suspend fun adopt(descriptor: FileDescriptor)

    suspend fun forget(fileId: FileId)

    /** Record an access for LRU: called once per serve and on GUI open. */
    suspend fun touch(fileId: FileId)

    /** Delete partial copies left by imports a previous run did not finish. */
    suspend fun cleanTmp()
}

internal class DefaultFileStore(
    private val descriptors: FileDescriptorStore,
    private val crypto: CryptoProvider,
    private val files: FileAccess,
    private val storeDirectory: Path,
    private val config: StateFlow<OrchestratorConfig>,
    private val clock: Clock = Clock.System,
) : FileStore {
    override suspend fun import(
        source: FileImportSource,
        fileName: String,
        mimeType: String?,
        keepCopy: Boolean?,
        onBytes: (Long) -> Unit,
    ): ImportResult {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        val totalBytes = sourceSize(source) ?: return ImportResult.SourceUnreadable
        val picked = Picked(source, fileName, mimeType, totalBytes, blockSizeFor(totalBytes), onBytes)
        val copy = keepCopy ?: (totalBytes <= config.value.copyIntoStoreMaxBytes)
        return if (copy) importCopy(picked) else importReference(picked)
    }

    /**
     * Hash in place. A SOURCE_LOST descriptor whose bytes these are (same `fileId`) takes the
     * picked file as its new home and becomes a REFERENCE, also when it was a lost COPY: quota
     * only ever deletes files inside the store.
     */
    private suspend fun importReference(picked: Picked): ImportResult {
        val hashes = hashSource(picked, writeTo = null) ?: return ImportResult.SourceUnreadable
        val fileId = FileIds.fileIdForBlocks(crypto, picked.totalBytes, picked.blockSize, hashes)
        val now = clock.now()
        val existing = descriptors.findById(fileId)
        val (path, kind) = when (val s = picked.source) {
            is FileImportSource.PathSource -> s.path.toString() to FilePathKind.PATH
            is FileImportSource.ContentUriSource -> s.uri to FilePathKind.CONTENT_URI
        }
        val reference = picked.descriptor(fileId, hashes, FileStorage.REFERENCE, path, kind, existing, now)
        return when {
            existing == null -> {
                descriptors.upsert(reference)
                ImportResult.Imported(reference, created = true)
            }

            existing.status == FileDescriptorStatus.SOURCE_LOST -> {
                descriptors.markHeld(reference)
                ImportResult.Imported(reference, created = false)
            }

            else -> {
                descriptors.touch(fileId, now)
                ImportResult.Imported(existing.copy(lastAccessAt = now), created = false)
            }
        }
    }

    /**
     * Hash while copying into a tmp file, then move it into place under its `fileId`. An existing
     * REFERENCE is upgraded to COPY and a lost COPY is restored from the tmp, which holds the
     * verified bytes. The tmp is deleted on every other path, cancellation included.
     */
    private suspend fun importCopy(picked: Picked): ImportResult {
        files.createDirectories(storeDirectory)
        if (files.freeSpaceBytes(storeDirectory) < picked.totalBytes) return ImportResult.NoSpace
        val tmp = Path(storeDirectory.toString(), "$TMP_PREFIX${Uuid.random()}")
        var moved = false
        try {
            val hashes = hashSource(picked, writeTo = tmp) ?: return ImportResult.SourceUnreadable
            val fileId = FileIds.fileIdForBlocks(crypto, picked.totalBytes, picked.blockSize, hashes)
            val now = clock.now()
            val existing = descriptors.findById(fileId)
            if (existing?.storage == FileStorage.COPY && existing.status == FileDescriptorStatus.HELD) {
                descriptors.touch(fileId, now)
                return ImportResult.Imported(existing.copy(lastAccessAt = now), created = false)
            }
            val final = Path(storeDirectory.toString(), fileId.value)
            files.move(tmp, final)
            moved = true
            files.syncDirectory(final)
            val copy = picked.descriptor(
                fileId, hashes, FileStorage.COPY, final.toString(), FilePathKind.PATH, existing, now,
            )
            if (existing == null) {
                descriptors.upsert(copy)
                return ImportResult.Imported(copy, created = true)
            }
            descriptors.markHeld(copy)
            return ImportResult.Imported(copy, created = false)
        } finally {
            if (!moved) withContext(NonCancellable) { files.delete(tmp) }
        }
    }

    override suspend fun markSourceLost(fileId: FileId) {
        descriptors.setStatus(fileId, FileDescriptorStatus.SOURCE_LOST)
    }

    override suspend fun adopt(descriptor: FileDescriptor) = descriptors.upsert(descriptor)

    override suspend fun forget(fileId: FileId) = descriptors.deleteById(fileId)

    override suspend fun touch(fileId: FileId) = descriptors.touch(fileId, clock.now())

    override suspend fun cleanTmp() = files.cleanTmpFiles(storeDirectory, TMP_PREFIX)

    private suspend fun sourceSize(source: FileImportSource): Long? = when (source) {
        is FileImportSource.PathSource -> files.fileSize(source.path)
        is FileImportSource.ContentUriSource -> files.contentSize(source.uri)
    }

    /**
     * Block hashes of the source, written to [writeTo] on the way when given. Null when the
     * source cannot be opened or read to its size. Write failures propagate.
     */
    private suspend fun hashSource(picked: Picked, writeTo: Path?): List<ByteArray>? {
        val writer: AppendHandle? = writeTo?.let { files.openAppendWriter(it, null) }
        writer.use {
            if (picked.totalBytes == 0L) {
                writer?.flushAndSync()
                return emptyList()
            }
            val reader = openSource(picked.source) ?: return null
            reader.use {
                val hashes = mutableListOf<ByteArray>()
                var hasher = crypto.sha256Hasher()
                var inBlock = 0L
                var offset = 0L
                val buf = ByteArray(128 * 1024)
                while (offset < picked.totalBytes) {
                    val want = minOf(buf.size.toLong(), picked.totalBytes - offset).toInt()
                    val got = readFully(reader, offset, buf, want) ?: return null
                    if (got <= 0) return null
                    hasher.update(buf, 0, got)
                    writer?.write(buf, 0, got)
                    offset += got
                    inBlock += got
                    picked.onBytes(got.toLong())
                    if (inBlock == picked.blockSize.toLong() || offset == picked.totalBytes) {
                        hashes += hasher.digest()
                        hasher = crypto.sha256Hasher()
                        inBlock = 0
                    }
                }
                writer?.flushAndSync()
                return hashes
            }
        }
    }

    private suspend fun openSource(source: FileImportSource): PositionedHandle? = try {
        when (source) {
            is FileImportSource.PathSource -> files.openPositionedReader(source.path)
            is FileImportSource.ContentUriSource -> files.openContentPositionedReader(source.uri)
        }
    } catch (e: IOException) {
        null
    }

    /** Bytes read into `buf[0, want)`, fewer only at the source's end; null on a read error. */
    private suspend fun readFully(reader: PositionedHandle, offset: Long, buf: ByteArray, want: Int): Int? {
        var got = 0
        try {
            while (got < want) {
                val n = reader.readAt(offset + got, buf, got, want - got)
                if (n <= 0) break
                got += n
            }
        } catch (e: IOException) {
            return null
        }
        return got
    }

    private class Picked(
        val source: FileImportSource,
        val fileName: String,
        val mimeType: String?,
        val totalBytes: Long,
        val blockSize: Int,
        val onBytes: (Long) -> Unit,
    ) {
        fun descriptor(
            fileId: FileId,
            hashes: List<ByteArray>,
            storage: FileStorage,
            path: String,
            pathKind: FilePathKind,
            existing: FileDescriptor?,
            now: Instant,
        ) = FileDescriptor(
            fileId = fileId,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = totalBytes,
            blockSize = blockSize,
            blockHashes = hashes,
            storage = storage,
            path = path,
            pathKind = pathKind,
            status = FileDescriptorStatus.HELD,
            createdAt = existing?.createdAt ?: now,
            lastAccessAt = now,
        )
    }

    private companion object {
        const val TMP_PREFIX = "tmp-"
    }
}
