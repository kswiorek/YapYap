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
}
