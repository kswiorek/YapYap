package org.yapyap.transport.tor

import org.yapyap.transport.tor.backend.TOR_PROBE_MAGIC
import org.yapyap.transport.tor.backend.buildProbePayload
import org.yapyap.transport.tor.backend.probeNonceOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorProbeTest {

    @Test
    fun probePayload_roundTripsNonce() {
        val nonce = ByteArray(16) { it.toByte() }
        val payload = buildProbePayload(nonce)
        assertTrue(payload.size >= TOR_PROBE_MAGIC.size + 16)
        assertContentEquals(nonce, probeNonceOrNull(payload))
    }

    @Test
    fun probeMatcher_rejectsNonProbePayloads() {
        assertNull(probeNonceOrNull(byteArrayOf()))
        assertNull(probeNonceOrNull("hello".encodeToByteArray()))
        assertNull(probeNonceOrNull(TOR_PROBE_MAGIC))
        assertNull(probeNonceOrNull(TOR_PROBE_MAGIC + ByteArray(15)))
        // Right prefix but truncated nonce.
        assertNull(probeNonceOrNull(TOR_PROBE_MAGIC + ByteArray(4) { 1 }))
    }

    @Test
    fun probeMatcher_distinguishesNonces() {
        val a = buildProbePayload(ByteArray(16) { 1 })
        val b = buildProbePayload(ByteArray(16) { 2 })
        val nonceA = probeNonceOrNull(a)!!
        assertTrue(nonceA.contentEquals(ByteArray(16) { 1 }))
        assertTrue(!nonceA.contentEquals(probeNonceOrNull(b)!!))
    }
}
