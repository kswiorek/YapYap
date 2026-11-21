package org.yapyap.orchestrator.file

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.FILE_BLOCK_BASE_SIZE_BYTES
import org.yapyap.protocol.FileId
import org.yapyap.protocol.blockSizeFor
import org.yapyap.testfixtures.TestFileIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileIdsTest {
    private val crypto = DefaultCryptoProvider()
    private val mib = 1024 * 1024

    @Test
    fun blockSize_emptyUsesBase() {
        assertEquals(FILE_BLOCK_BASE_SIZE_BYTES, blockSizeFor(0))
    }

    @Test
    fun blockSize_smallFilesUse1MiB() {
        assertEquals(mib, blockSizeFor(1))
        assertEquals(mib, blockSizeFor(mib.toLong()))
        assertEquals(mib, blockSizeFor(10L * mib))
    }

    @Test
    fun blockSize_doublesAt1024Blocks() {
        val oneGib = 1024L * mib
        assertEquals(mib, blockSizeFor(oneGib))
        assertEquals(2 * mib, blockSizeFor(oneGib + 1))
    }

    @Test
    fun fileId_emptyFile_stableAndVerifies() = runTest {
        val fileId = TestFileIds.fileIdForBytes(crypto, byteArrayOf())
        assertEquals(blockSizeFor(0), FILE_BLOCK_BASE_SIZE_BYTES)
        assertTrue(FileIds.checkBlockList(crypto, fileId, 0, blockSizeFor(0), emptyList()))
    }

    @Test
    fun fileId_oneShortBlock_roundTrip() = runTest {
        val data = "hello".encodeToByteArray()
        val fileId = TestFileIds.fileIdForBytes(crypto, data)
        val blockSize = blockSizeFor(data.size.toLong())
        val hashes = TestFileIds.blockHashesForBytes(crypto, data, blockSize)
        assertEquals(1, hashes.size)
        assertTrue(FileIds.checkBlockList(crypto, fileId, data.size.toLong(), blockSize, hashes))
    }

    @Test
    fun fileId_exactlyOneBlock_roundTrip() = runTest {
        val data = ByteArray(mib) { it.toByte() }
        val fileId = TestFileIds.fileIdForBytes(crypto, data)
        val blockSize = blockSizeFor(data.size.toLong())
        assertEquals(mib, blockSize)
        val hashes = TestFileIds.blockHashesForBytes(crypto, data, blockSize)
        assertEquals(1, hashes.size)
        assertTrue(FileIds.checkBlockList(crypto, fileId, data.size.toLong(), blockSize, hashes))
    }

    @Test
    fun fileId_exactMultiple_roundTrip() = runTest {
        val data = ByteArray(2 * mib) { (it % 251).toByte() }
        val fileId = TestFileIds.fileIdForBytes(crypto, data)
        val blockSize = blockSizeFor(data.size.toLong())
        val hashes = TestFileIds.blockHashesForBytes(crypto, data, blockSize)
        assertEquals(2, hashes.size)
        assertTrue(FileIds.checkBlockList(crypto, fileId, data.size.toLong(), blockSize, hashes))
    }

    @Test
    fun fileId_shortLastBlock_roundTrip() = runTest {
        val data = ByteArray(mib + 13) { (it * 7).toByte() }
        val fileId = TestFileIds.fileIdForBytes(crypto, data)
        val blockSize = blockSizeFor(data.size.toLong())
        val hashes = TestFileIds.blockHashesForBytes(crypto, data, blockSize)
        assertEquals(2, hashes.size)
        assertTrue(FileIds.checkBlockList(crypto, fileId, data.size.toLong(), blockSize, hashes))
    }

    @Test
    fun checkBlockList_rejectsMismatch() = runTest {
        val data = "content".encodeToByteArray()
        val fileId = TestFileIds.fileIdForBytes(crypto, data)
        val blockSize = blockSizeFor(data.size.toLong())
        val hashes = TestFileIds.blockHashesForBytes(crypto, data, blockSize).toMutableList()
        hashes[0] = hashes[0].copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        assertFalse(FileIds.checkBlockList(crypto, fileId, data.size.toLong(), blockSize, hashes))
    }

    @Test
    fun checkBlockList_rejectsWrongBlockSize() = runTest {
        val data = ByteArray(10) { 1 }
        val fileId = TestFileIds.fileIdForBytes(crypto, data)
        val hashes = TestFileIds.blockHashesForBytes(crypto, data, mib)
        assertFalse(FileIds.checkBlockList(crypto, FileId(fileId.value), data.size.toLong(), 2 * mib, hashes))
    }

    @Test
    fun fileId_fixedVectors_matchIndependentHashes() = runTest {
        // Vectors computed independently (Python hashlib), not round-tripped.
        assertEquals(
            FileId("13899ea2aaf8b6cd8514832841864a373ae8167fdb2f70dd357301faafc3163f"),
            TestFileIds.fileIdForBytes(crypto, byteArrayOf()),
        )
        assertEquals(
            FileId("4e926c8998d2b8ab309434f9004890d7d8fc4ca05e0b1ff48997094d63583a39"),
            TestFileIds.fileIdForBytes(crypto, "hello".encodeToByteArray()),
        )
        assertEquals(
            FileId("ae6538f59959ddbed5fd61907d10826de6510ad3bdd60d816c494ff79a43f606"),
            TestFileIds.fileIdForBytes(crypto, ByteArray(mib) { it.toByte() }),
        )
        assertEquals(
            FileId("fa7a10d1e5ebf0aac852103b7e7d1f099576962f637266debd73986d77759bd7"),
            TestFileIds.fileIdForBytes(crypto, ByteArray(2 * mib) { (it % 251).toByte() }),
        )
        assertEquals(
            FileId("cfcab623450325c3d2cf21c174a409dc0fdf3be648f6da3290c0f786c0e972e6"),
            TestFileIds.fileIdForBytes(crypto, ByteArray(mib + 13) { (it * 7).toByte() }),
        )
        // The 1024-block edge, with synthetic block hashes (independent preimages). At exactly
        // 1 GiB the rule keeps 1 MiB blocks (1024 of them); one byte more doubles the block size
        // to 2 MiB (513 blocks). Both block sizes come from blockSizeFor, so the vectors pin it.
        val oneGib = 1024L * mib
        assertEquals(
            FileId("9d28e22bf40d763f8fe6602caaa57c76c5d9291731036b7221759090f4867bd3"),
            FileIds.fileIdForBlocks(crypto, oneGib, blockSizeFor(oneGib), syntheticHashes(1024)),
        )
        assertEquals(
            FileId("ea163dea012e01bd20aaf8caf5aee6ac353442772b6339e49e7bdbdbd5f07c1d"),
            FileIds.fileIdForBlocks(crypto, oneGib + 1, blockSizeFor(oneGib + 1), syntheticHashes(513)),
        )
    }

    private fun syntheticHashes(count: Int): List<ByteArray> = List(count) { i -> ByteArray(32) { (i % 256).toByte() } }
}
