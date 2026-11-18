package org.yapyap.orchestrator.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.protocol.FileId
import org.yapyap.protocol.blockSizeFor
import kotlin.time.Clock

/**
 * Permanent attachment store (design §10).
 *
 * - COPY: bytes live under the store dir as `<fileId hex>`, hashed in the same
 *   pass as the copy. Survives the original being deleted. Quota-managed with
 *   LRU over copies only.
 * - REFERENCE: the original stays in place and is hashed in place. Outside the
 *   quota, never deleted by retention. A moved/deleted/edited original is
 *   caught by the block check while serving (step 9) and marked SOURCE_LOST.
 *
 * The import result carries the `fileId` and block list. The GUI only gets
 * paths and metadata vended back (guide §2, design §10).
 */
internal interface FileStore {
    suspend fun import(
        source: FileImportSource,
        fileName: String,
        mimeType: String?,
        keepCopy: Boolean,
    ): FileDescriptor

    suspend fun resolve(fileId: FileId): FileDescriptor?

    suspend fun markSourceLost(fileId: FileId)

    /** Enforce the copy quota now (used after a receive lands in the permanent store). */
    suspend fun enforceQuota()
}

internal class DefaultFileStore(
    private val descriptors: FileDescriptorStore,
    private val crypto: CryptoProvider,
    private val files: FileAccess,
    private val storeDirectory: Path,
    private val config: FileServiceConfig,
    private val clock: Clock = Clock.System,
) : FileStore {
    override suspend fun import(
        source: FileImportSource,
        fileName: String,
        mimeType: String?,
        keepCopy: Boolean,
    ): FileDescriptor = withContext(Dispatchers.IO) {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        val totalBytes = sourceSize(source)
            ?: throw IllegalArgumentException("import source is missing")
        val blockSize = blockSizeFor(totalBytes)
        val copy = keepCopy

        if (!copy) {
            val hashes = hashSource(source, totalBytes, blockSize, writeTo = null)
            val fileId = FileIds.fileIdForBlocks(crypto, totalBytes, blockSize, hashes)
            val existing = descriptors.findById(fileId)
            if (existing != null) return@withContext existing
            val descriptor = FileDescriptor(
                fileId = fileId,
                fileName = fileName,
                mimeType = mimeType,
                totalBytes = totalBytes,
                blockSize = blockSize,
                blockHashes = hashes,
                storage = FileStorage.REFERENCE,
                path = when (source) {
                    is FileImportSource.PathSource -> source.path.toString()
                    is FileImportSource.ContentUriSource -> source.uri
                },
                status = FileDescriptorStatus.HELD,
                createdAt = clock.now(),
            )
            descriptors.upsert(descriptor)
            return@withContext descriptor
        }

        // Copy mode: stream source -> temp store file while hashing per block.
        files.createDirectories(storeDirectory)
        val tmp = Path(storeDirectory.toString(), "tmp-${clock.now().toEpochMilliseconds()}-${fileName.hashCode()}")
        val hashes: List<ByteArray>
        try {
            hashes = hashSource(source, totalBytes, blockSize, writeTo = tmp)
        } catch (e: Exception) {
            runCatching { files.delete(tmp) }
            throw e
        }
        val fileId = FileIds.fileIdForBlocks(crypto, totalBytes, blockSize, hashes)
        val existing = descriptors.findById(fileId)
        if (existing != null) {
            runCatching { files.delete(tmp) }
            return@withContext existing
        }
        val final = Path(storeDirectory.toString(), fileId.value)
        if (files.exists(final)) {
            runCatching { files.delete(tmp) }
        } else {
            files.move(tmp, final)
        }
        val descriptor = FileDescriptor(
            fileId = fileId,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = totalBytes,
            blockSize = blockSize,
            blockHashes = hashes,
            storage = FileStorage.COPY,
            path = final.toString(),
            status = FileDescriptorStatus.HELD,
            createdAt = clock.now(),
        )
        descriptors.upsert(descriptor)
        enforceQuota(descriptor.fileId)
        descriptors.findById(fileId) ?: descriptor
    }

    override suspend fun resolve(fileId: FileId): FileDescriptor? = descriptors.findById(fileId)

    override suspend fun markSourceLost(fileId: FileId) {
        descriptors.setStatus(fileId, FileDescriptorStatus.SOURCE_LOST)
    }

    override suspend fun enforceQuota() = enforceQuota(newFileId = null)

    private suspend fun sourceSize(source: FileImportSource): Long? = when (source) {
        is FileImportSource.PathSource -> files.fileSize(source.path)
        is FileImportSource.ContentUriSource -> files.contentSize(source.uri)
    }

    /**
     * Hashes every block of [source] in order. When [writeTo] is set the bytes
     * are also appended to that store path in the same pass (copy mode).
     */
    private suspend fun hashSource(
        source: FileImportSource,
        totalBytes: Long,
        blockSize: Int,
        writeTo: Path?,
    ): List<ByteArray> {
        if (totalBytes == 0L) {
            writeTo?.let { t ->
                files.openAppendWriter(t, null).use { it.flushAndSync() }
            }
            return emptyList()
        }
        val writer = writeTo?.let { files.openAppendWriter(it, null) }
        val reader = when (source) {
            is FileImportSource.PathSource -> files.openPositionedReader(source.path)
            is FileImportSource.ContentUriSource -> files.openContentPositionedReader(source.uri)
                ?: throw IllegalArgumentException("content URI cannot be read: $source")
        }
        try {
            val hashes = mutableListOf<ByteArray>()
            var hasher = crypto.sha256Hasher()
            var inBlock = 0L
            var remaining = totalBytes
            var offset = 0L
            val buf = ByteArray(128 * 1024)
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                var got = 0
                while (got < want) {
                    val n = reader.readAt(offset + got, buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                if (got <= 0) throw IllegalArgumentException("import source ended early at offset $offset")
                hasher.update(buf, 0, got)
                writer?.write(buf, 0, got)
                offset += got
                remaining -= got
                inBlock += got
                val blockDone = inBlock == blockSize.toLong() || remaining == 0L
                if (blockDone) {
                    hashes += hasher.digest()
                    if (remaining > 0) {
                        hasher = crypto.sha256Hasher()
                        inBlock = 0
                    }
                }
            }
            writer?.flushAndSync()
            return hashes
        } finally {
            runCatching { reader.close() }
            runCatching { writer?.close() }
        }
    }

    /**
     * LRU over copies only (design §10). Eviction removes the descriptor and
     * deletes the stored bytes. Receive-side EVICTED marking lands with the
     * receive engine (step 12); evicted files are never auto-re-fetched.
     */
    private suspend fun enforceQuota(newFileId: org.yapyap.protocol.FileId? = null) {
        var total = descriptors.copyBytesTotal()
        if (total <= config.storeQuotaBytes) return
        for (victim in descriptors.copyDescriptorsOldestFirst()) {
            if (total <= config.storeQuotaBytes) break
            // Never evict the file just imported when it alone exceeds quota.
            if (victim.fileId == newFileId) continue
            // Eviction removes the descriptor and deletes the stored bytes.
            descriptors.deleteById(victim.fileId)
            runCatching { files.delete(Path(victim.path)) }
            total -= victim.totalBytes
        }
    }
}
