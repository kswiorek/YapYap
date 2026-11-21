package org.yapyap.testfixtures

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.file.FileIds
import org.yapyap.protocol.FileId
import org.yapyap.protocol.blockSizeFor

/** Test-only byte helpers, moved out of `FileIds` (production hashes incrementally). */
internal object TestFileIds {
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

    suspend fun fileIdForBytes(crypto: CryptoProvider, data: ByteArray): FileId {
        val totalBytes = data.size.toLong()
        val blockSize = blockSizeFor(totalBytes)
        val hashes = blockHashesForBytes(crypto, data, blockSize)
        return FileIds.fileIdForBlocks(crypto, totalBytes, blockSize, hashes)
    }
}
