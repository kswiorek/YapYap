package org.yapyap.wiring

import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.FileFetchFrame
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.routing.router.TransportLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileLimitsTest {

    private val defaults = fileLimitsFor(RuntimeConfig())

    @Test
    fun chunkSize_isTheSmallerTransportFrameLessEveryLayersFraming() {
        val webRtc = RuntimeConfig().webRtc.maxPayloadBytes
        val framing = BinaryEnvelope.ENCODED_OVERHEAD_BYTES + FileHeader.CHUNK_ENCODED_BYTES +
                FileProtection.CHUNK_OVERHEAD_BYTES
        assertEquals(webRtc - framing, defaults.chunkSizeBytes)
        assertTrue(defaults.chunkSizeBytes in 240 * 1024..250 * 1024, "≈249 KiB, got ${defaults.chunkSizeBytes}")
    }

    @Test
    fun chunkSize_followsTheSmallerTransport() {
        val limits =
            fileLimitsFor(TransportLimits(torMaxPayloadBytes = 100 * 1024, webRtcMaxPayloadBytes = 250 * 1024))
        assertEquals(defaults.chunkSizeBytes - 150 * 1024, limits.chunkSizeBytes)
    }

    @Test
    fun fetchFrame_isTheWebRtcFrameLessTheDataKind() {
        val webRtc = RuntimeConfig().webRtc.maxPayloadBytes
        assertEquals(webRtc - FileFetchFrame.Data.ENCODED_OVERHEAD_BYTES, defaults.fetchFrameBytes)
        // Tor never carries fetch frames.
        val smallTor = fileLimitsFor(TransportLimits(torMaxPayloadBytes = 100 * 1024, webRtcMaxPayloadBytes = webRtc))
        assertEquals(defaults.fetchFrameBytes, smallTor.fetchFrameBytes)
    }

    @Test
    fun tinyTransport_failsInsteadOfClamping() {
        assertFailsWith<IllegalArgumentException> {
            fileLimitsFor(TransportLimits(torMaxPayloadBytes = 1024, webRtcMaxPayloadBytes = 1024))
        }
    }
}
