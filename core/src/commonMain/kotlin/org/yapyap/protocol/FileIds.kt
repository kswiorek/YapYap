package org.yapyap.protocol

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * Content identity, block-hashed (design §2).
 * Hex-encoded SHA-256 over the file size, block size and per-block hashes.
 */
@JvmInline
public value class FileId(public val value: String) {
    init {
        require(value.isNotBlank()) { "FileId cannot be blank" }
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "FileId must be 64 lowercase hex chars"
        }
    }
}

/**
 * Routing session identity (small path only). One push/serve attempt between two devices.
 */
@JvmInline
public value class TransferId(public val value: Uuid)

/** Network value, not config: small/large threshold (design §1). Until the network config exists. */
internal const val SMALL_FILE_MAX_BYTES: Long = 10L * 1024L * 1024L

/** Block-size rule constants (design §2): protocol constants, not config. */
internal const val FILE_BLOCK_BASE_SIZE_BYTES: Int = 1024 * 1024
internal const val FILE_BLOCK_MAX_COUNT: Int = 1024
internal const val FILE_ID_DOMAIN: String = "yapyap-file-v1"

/**
 * Derives the block size from [totalBytes] alone: start at 1 MiB and double
 * until the file has at most [FILE_BLOCK_MAX_COUNT] blocks.
 * Empty files use the base size (no blocks).
 */
internal fun blockSizeFor(totalBytes: Long): Int {
    require(totalBytes >= 0) { "totalBytes must be >= 0 but was $totalBytes" }
    if (totalBytes == 0L) return FILE_BLOCK_BASE_SIZE_BYTES
    var blockSize = FILE_BLOCK_BASE_SIZE_BYTES.toLong()
    while ((totalBytes + blockSize - 1) / blockSize > FILE_BLOCK_MAX_COUNT) {
        blockSize *= 2
        require(blockSize <= Int.MAX_VALUE) { "block size overflow for totalBytes=$totalBytes" }
    }
    return blockSize.toInt()
}

/**
 * Builds the `fileId` preimage (design §2):
 * `"yapyap-file-v1" ‖ u64 totalBytes BE ‖ u32 blockSize BE ‖ h₀ … hₙ₋₁`.
 * Hashing happens outside `protocol` (the `RoomIds` pattern).
 */
internal fun buildFileIdPreimage(
    totalBytes: Long,
    blockSize: Int,
    blockHashes: List<ByteArray>,
): ByteArray {
    require(totalBytes >= 0) { "totalBytes must be >= 0" }
    require(blockSize > 0) { "blockSize must be > 0" }
    val domain = FILE_ID_DOMAIN.encodeToByteArray()
    val writer = ByteWriter(domain.size + 8 + 4 + blockHashes.size * 32)
    writer.writeBytes(domain)
    writer.writeLong(totalBytes)
    writer.writeInt(blockSize)
    for (h in blockHashes) {
        require(h.size == 32) { "block hash must be 32 bytes" }
        writer.writeBytes(h)
    }
    return writer.toByteArray()
}
