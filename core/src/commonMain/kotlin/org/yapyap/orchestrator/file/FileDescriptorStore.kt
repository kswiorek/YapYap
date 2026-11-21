package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.File_descriptors
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.FileId
import kotlin.time.Instant

internal fun encodeBlockHashes(hashes: List<ByteArray>): ByteArray {
    hashes.forEach { require(it.size == 32) { "block hash must be 32 bytes" } }
    val out = ByteArray(hashes.size * 32)
    hashes.forEachIndexed { i, h -> h.copyInto(out, i * 32) }
    return out
}

internal fun decodeBlockHashes(blob: ByteArray): List<ByteArray> {
    require(blob.size % 32 == 0) { "block_hashes blob must be a multiple of 32 but was ${blob.size}" }
    return List(blob.size / 32) { i -> blob.copyOfRange(i * 32, (i + 1) * 32) }
}

/** Read-only view of `file_descriptors`, for everyone but its writer, the [FileStore]. */
internal interface FileDescriptorReader {
    suspend fun findById(fileId: FileId): FileDescriptor?

    /** Least recently accessed COPY descriptors first: true LRU for quota eviction (read-only). */
    suspend fun copyDescriptorsLeastRecentlyUsed(): List<FileDescriptor>
    suspend fun copyBytesTotal(): Long
}

/**
 * Owner of `file_descriptors` (guide §4). Generated `fileQueries` stay here. Only the [FileStore]
 * holds this interface; everyone else reads through [FileDescriptorReader].
 */
internal interface FileDescriptorStore : FileDescriptorReader {
    suspend fun upsert(descriptor: FileDescriptor)
    suspend fun deleteById(fileId: FileId)
    suspend fun setStatus(fileId: FileId, status: FileDescriptorStatus)
    suspend fun touch(fileId: FileId, now: Instant)
    suspend fun markHeld(descriptor: FileDescriptor)
}

internal class DefaultFileDescriptorStore(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : FileDescriptorStore {
    private val queries = database.fileQueries

    override suspend fun upsert(descriptor: FileDescriptor): Unit = withContext(dbDispatcher) {
        queries.insertFileDescriptor(
            file_id = descriptor.fileId,
            file_name = descriptor.fileName,
            mime_type = descriptor.mimeType,
            total_bytes = descriptor.totalBytes,
            block_size = descriptor.blockSize.toLong(),
            block_hashes = encodeBlockHashes(descriptor.blockHashes),
            storage = descriptor.storage,
            path = descriptor.path,
            storage_kind = descriptor.pathKind,
            status = descriptor.status,
            created_at = descriptor.createdAt,
            last_access_at = descriptor.lastAccessAt,
        )
    }

    override suspend fun findById(fileId: FileId): FileDescriptor? = withContext(dbDispatcher) {
        queries.selectFileDescriptorById(fileId).executeAsOneOrNull()?.toDescriptor()
    }

    override suspend fun deleteById(fileId: FileId): Unit = withContext(dbDispatcher) {
        queries.deleteFileDescriptorById(fileId)
    }

    override suspend fun setStatus(fileId: FileId, status: FileDescriptorStatus): Unit = withContext(dbDispatcher) {
        queries.updateFileDescriptorStatus(status, fileId)
    }

    override suspend fun touch(fileId: FileId, now: Instant): Unit = withContext(dbDispatcher) {
        queries.updateFileDescriptorAccess(now, fileId)
    }

    override suspend fun markHeld(descriptor: FileDescriptor): Unit = withContext(dbDispatcher) {
        queries.updateFileDescriptorHeld(
            file_name = descriptor.fileName,
            mime_type = descriptor.mimeType,
            total_bytes = descriptor.totalBytes,
            block_size = descriptor.blockSize.toLong(),
            block_hashes = encodeBlockHashes(descriptor.blockHashes),
            storage = descriptor.storage,
            path = descriptor.path,
            storage_kind = descriptor.pathKind,
            last_access_at = descriptor.lastAccessAt,
            file_id = descriptor.fileId,
        )
    }

    override suspend fun copyDescriptorsLeastRecentlyUsed(): List<FileDescriptor> = withContext(dbDispatcher) {
        queries.selectCopyDescriptorsOrderedByAccess().executeAsList().map { it.toDescriptor() }
    }

    override suspend fun copyBytesTotal(): Long = withContext(dbDispatcher) {
        queries.sumCopyBytes().executeAsOneOrNull() ?: 0L
    }

    private fun File_descriptors.toDescriptor(): FileDescriptor = FileDescriptor(
        fileId = file_id,
        fileName = file_name,
        mimeType = mime_type,
        totalBytes = total_bytes,
        blockSize = block_size.toInt(),
        blockHashes = decodeBlockHashes(block_hashes),
        storage = storage,
        path = path,
        pathKind = storage_kind,
        status = status,
        createdAt = created_at,
        lastAccessAt = last_access_at,
    )
}
