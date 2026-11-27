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
 * One hop of the small path: a transfer between two devices moving a range of one seal's chunks
 * (docs/envelope-redesign.md §5.3). Every hop has its own.
 */
@JvmInline
public value class TransferId(public val value: Uuid)

/** Shared file-name cap: attachments, the seal and PULL_OK (design §3). */
internal const val MAX_FILE_NAME_CHARS: Int = 80
internal const val MAX_FILE_NAME_BYTES: Int = 255

/** Shared display-metadata caps for the seal and PULL_OK. */
internal const val MAX_MIME_TYPE_CHARS: Int = 128
internal const val MAX_MIME_TYPE_BYTES: Int = 128

/**
 * Smallest small-path chunk a receiver or relay accepts. A transfer's chunk granularity is
 * peer-chosen in its OFFER and every accepted chunk costs a stored row at a relay, so this floor
 * is a storage-amplification bound on attacker-chosen granularity (one byte per chunk would turn a
 * 10 MiB deposit into ten million rows). A format contract, not config: there is no fallback
 * granularity, so every device must agree on it.
 */
internal const val MIN_FILE_CHUNK_SIZE_BYTES: Int = 64 * 1024

/** Exact byte length of every block hash on the wire. */
internal const val FILE_HASH_BYTES: Int = 32

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
