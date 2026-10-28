package org.yapyap.gui.screens.setup

import kotlin.test.*

class InviteCodeTest {

    @Test
    fun roundTrip() {
        val bytes = ByteArray(64) { it.toByte() }
        val encoded = Base64Encoder.encode(bytes)
        val decoded = Base64Encoder.decode(encoded)
        assertNotNull(decoded)
        assertTrue(bytes.contentEquals(decoded))
    }

    @Test
    fun roundTripEmptyPayloadFailsCleanly() {
        // encode(empty) is "" and decode("") is null — the dialog treats it as
        // "ask for a fresh one", never a crash.
        assertNull(Base64Encoder.decode(Base64Encoder.encode(byteArrayOf())))
    }

    @Test
    fun whitespaceTolerated() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = Base64Encoder.encode(bytes)
        val wrapped = encoded.chunked(4).joinToString("\n")
        val decoded = Base64Encoder.decode("  $wrapped  \n")
        assertNotNull(decoded)
        assertTrue(bytes.contentEquals(decoded))
    }

    @Test
    fun malformedIsNull() {
        assertNull(Base64Encoder.decode(""))
        assertNull(Base64Encoder.decode("   "))
        assertNull(Base64Encoder.decode("!!!"))
        assertNull(Base64Encoder.decode("ABC")) // bad length
        assertNull(Base64Encoder.decode("AB=C")) // padding mid-string pattern
        assertNull(Base64Encoder.decode("A==="))
    }

    @Test
    fun knownVector() {
        assertEquals("TWFu", Base64Encoder.encode("Man".encodeToByteArray()))
        assertEquals("Man", Base64Encoder.decode("TWFu")!!.decodeToString())
    }
}
