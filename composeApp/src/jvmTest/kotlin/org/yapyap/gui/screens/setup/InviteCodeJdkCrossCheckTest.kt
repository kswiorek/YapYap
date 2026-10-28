package org.yapyap.gui.screens.setup

import kotlin.random.Random
import kotlin.test.*

/**
 * Cross-checks the hand-rolled commonMain codec against `java.util.Base64`
 * (ground truth, JVM only). If this is green, a "code isn't valid" error from
 * the sponsor dialog cannot be caused by the GUI's base64 layer mangling or
 * mis-decoding the pasted text.
 */
class InviteCodeJdkCrossCheckTest {

    private val jdk = java.util.Base64.getMimeDecoder()
    private val jdkEncoder = java.util.Base64.getEncoder()

    @Test
    fun encodeMatchesJdkForAllLengths() {
        val random = Random(42)
        for (size in 0..600) {
            val bytes = ByteArray(size) { random.nextInt(256).toByte() }
            assertEquals(jdkEncoder.encodeToString(bytes), Base64Encoder.encode(bytes), "size=$size")
        }
    }

    @Test
    fun decodeMatchesJdkForAllLengths() {
        val random = Random(7)
        for (size in 1..600) {
            val bytes = ByteArray(size) { random.nextInt(256).toByte() }
            val text = jdkEncoder.encodeToString(bytes)
            assertContentEquals(bytes, Base64Encoder.decode(text), "size=$size")
            // MIME-style wrapped input (pasted from a wrapped text view) must survive too.
            val wrapped = text.chunked(64).joinToString("\r\n")
            assertContentEquals(bytes, Base64Encoder.decode(wrapped), "wrapped size=$size")
        }
    }

    @Test
    fun decodeJdkVectorsWithOurDecoder() {
        // Every padding shape: no pad, one '=', two '=='.
        listOf("TWFu", "TWE=", "TQ==", "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo=").forEach { v ->
            assertContentEquals(jdk.decode(v), Base64Encoder.decode(v), v)
        }
    }

    @Test
    fun ourEncoderOutputDecodableByJdk() {
        val random = Random(99)
        for (size in 1..300) {
            val bytes = ByteArray(size) { random.nextInt(256).toByte() }
            assertContentEquals(bytes, jdk.decode(Base64Encoder.encode(bytes)), "size=$size")
        }
    }

    @Test
    fun nonAlphabetInputRejected() {
        assertNull(Base64Encoder.decode("****"))
        assertNull(Base64Encoder.decode("TWFu!"))
        assertNotNull(Base64Encoder.decode("TWFu"))
    }
}
