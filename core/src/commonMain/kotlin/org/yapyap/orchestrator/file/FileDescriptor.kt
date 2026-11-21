package org.yapyap.orchestrator.file

import org.yapyap.protocol.FileId
import kotlin.time.Instant

/** Where the bytes live (design §10). Copies are quota-managed; references never are. */
public enum class FileStorage {
    COPY,
    REFERENCE,
}

/** Whether [FileDescriptor.path] is a filesystem path or an Android content URI (design §10). */
public enum class FilePathKind {
    PATH,
    CONTENT_URI,
}

/** Holder-side liveness of a descriptor. A rotted reference stops being served. */
public enum class FileDescriptorStatus {
    HELD,
    SOURCE_LOST,
}

/**
 * Permanent store index entry (design §13 `file_descriptors`): what we hold.
 * Keys are per transfer and never stored here.
 */
internal data class FileDescriptor(
    val fileId: FileId,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
    val blockSize: Int,
    val blockHashes: List<ByteArray>,
    val storage: FileStorage,
    /** Store path for COPY; original path or content-URI string for REFERENCE. */
    val path: String,
    val pathKind: FilePathKind,
    val status: FileDescriptorStatus,
    val createdAt: Instant,
    val lastAccessAt: Instant,
)
