package org.yapyap.orchestrator.file

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.protocol.FileId
import org.yapyap.protocol.blockSizeFor
import org.yapyap.protocol.buildFileIdPreimage

/**
 * Block-hashed `fileId` construction (design §2).
 * Hashing lives here, outside `protocol` (the `RoomIds` pattern).
 */
internal object FileIds {
    suspend fun blockHashesForBytes(
        crypto: CryptoProvider,
        data: ByteArray,
        blockSize: Int,
    ): List<ByteArray> {
        require(blockSize > 0) { "blockSize must be > 0" }
        if (data.isEmpty()) return emptyList()
        val hashes = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val len = minOf(blockSize, data.size - offset)
            val hasher = crypto.sha256Hasher()
            hasher.update(data, offset, len)
            hashes += hasher.digest()
            offset += len
        }
        return hashes
    }

    suspend fun fileIdForBlocks(
        crypto: CryptoProvider,
        totalBytes: Long,
        blockSize: Int,
        blockHashes: List<ByteArray>,
    ): FileId {
        val preimage = buildFileIdPreimage(totalBytes, blockSize, blockHashes)
        return FileId(crypto.toHex(crypto.sha256(preimage)))
    }

    suspend fun fileIdForBytes(crypto: CryptoProvider, data: ByteArray): FileId {
        val totalBytes = data.size.toLong()
        val blockSize = blockSizeFor(totalBytes)
        val hashes = blockHashesForBytes(crypto, data, blockSize)
        return fileIdForBlocks(crypto, totalBytes, blockSize, hashes)
    }

    /**
     * Checks a block list against a `fileId`: size/count must match the
     * `blockSizeFor` rule and the recomputed hash must equal [fileId].
     */
    suspend fun checkBlockList(
        crypto: CryptoProvider,
        fileId: FileId,
        totalBytes: Long,
        blockSize: Int,
        blockHashes: List<ByteArray>,
    ): Boolean {
        if (blockSize != blockSizeFor(totalBytes)) return false
        val expectedBlocks = if (totalBytes == 0L) 0 else ((totalBytes + blockSize - 1) / blockSize).toInt()
        if (blockHashes.size != expectedBlocks) return false
        if (blockHashes.any { it.size != 32 }) return false
        return fileIdForBlocks(crypto, totalBytes, blockSize, blockHashes) == fileId
    }
}
