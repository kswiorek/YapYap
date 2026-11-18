package org.yapyap.crypto.primitives

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class Sha256HasherTest {
    private val crypto = DefaultCryptoProvider()

    @Test
    fun streaming_emptyInput_matchesOneShot() = runTest {
        val hasher = crypto.sha256Hasher()
        val streamed = hasher.digest()
        assertContentEquals(crypto.sha256(byteArrayOf()), streamed)
    }

    @Test
    fun streaming_splitInput_matchesOneShot() = runTest {
        val data = ByteArray(256) { it.toByte() }
        val expected = crypto.sha256(data)
        val hasher = crypto.sha256Hasher()
        hasher.update(data, 0, 100)
        hasher.update(data, 100, 156)
        assertContentEquals(expected, hasher.digest())
    }

    @Test
    fun streaming_oneBytePieces_matchesOneShot() = runTest {
        val data = "hello streaming hash".encodeToByteArray()
        val expected = crypto.sha256(data)
        val hasher = crypto.sha256Hasher()
        for (i in data.indices) {
            hasher.update(data, i, 1)
        }
        assertContentEquals(expected, hasher.digest())
    }

    @Test
    fun streaming_oddSizedPieces_matchesOneShot() = runTest {
        val data = ByteArray(1000) { (it * 31).toByte() }
        val expected = crypto.sha256(data)
        val hasher = crypto.sha256Hasher()
        var offset = 0
        val sizes = listOf(1, 7, 13, 3, 64, 129, 5)
        var si = 0
        while (offset < data.size) {
            val len = minOf(sizes[si % sizes.size], data.size - offset)
            hasher.update(data, offset, len)
            offset += len
            si++
        }
        assertContentEquals(expected, hasher.digest())
    }

    @Test
    fun streaming_offsetLength_sliceMatches() = runTest {
        val data = ByteArray(64) { it.toByte() }
        val slice = data.copyOfRange(10, 30)
        val expected = crypto.sha256(slice)
        val hasher = crypto.sha256Hasher()
        hasher.update(data, 10, 20)
        assertContentEquals(expected, hasher.digest())
    }

    @Test
    fun streaming_rejectsOutOfBounds() {
        val hasher = crypto.sha256Hasher()
        assertFailsWith<IllegalArgumentException> {
            hasher.update(byteArrayOf(1, 2), 1, 2)
        }
    }
}
