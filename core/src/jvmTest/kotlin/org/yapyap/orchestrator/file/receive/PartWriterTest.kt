package org.yapyap.orchestrator.file.receive

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.file.io.JvmFileAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class PartWriterTest {
    private val crypto = DefaultCryptoProvider()
    private val files = JvmFileAccess()
    private val writer = PartWriter(files, crypto)

    @Test
    fun appendAndVerify_roundTrip() = runTest {
        val dir = Path(SystemTemporaryDirectory.toString(), "yapyap-part-${Uuid.random()}")
        files.createDirectories(dir)
        val part = Path(dir.toString(), "f.part")
        val bytes = ByteArray(3000) { (it * 3).toByte() }
        writer.appendAt(part, 0, bytes.copyOfRange(0, 1000))
        writer.appendAt(part, 1000, bytes.copyOfRange(1000, 3000))
        // One block of 1 MiB covering all bytes: hash must verify.
        val hasher = crypto.sha256Hasher()
        hasher.update(bytes, 0, bytes.size)
        val expected = hasher.digest()
        assertIs<BlockCheck.Verified>(writer.verifyBlock(part, 0, 1024 * 1024, bytes.size.toLong(), expected))
        val bad = expected.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        assertIs<BlockCheck.Mismatch>(writer.verifyBlock(part, 0, 1024 * 1024, bytes.size.toLong(), bad))
    }

    @Test
    fun truncateTo_dropsTail() = runTest {
        val dir = Path(SystemTemporaryDirectory.toString(), "yapyap-part-${Uuid.random()}")
        files.createDirectories(dir)
        val part = Path(dir.toString(), "g.part")
        writer.appendAt(part, 0, ByteArray(1000) { 7 })
        writer.truncateTo(part, 400)
        assertEquals(400L, files.fileSize(part))
    }
}
