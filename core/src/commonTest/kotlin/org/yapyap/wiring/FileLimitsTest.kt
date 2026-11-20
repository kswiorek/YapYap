package org.yapyap.wiring

import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FileFetchFrame
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.router.TransportLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileLimitsTest {

    private val defaults = FileLimits.from(RuntimeConfig())

    @Test
    fun chunkSize_isTheSmallerTransportFrameLessEveryLayersFraming() {
        val webRtc = RuntimeConfig().webRtc.maxPayloadBytes
        val framing = BinaryEnvelope.ENCODED_HEADER_BYTES + FileEnvelope.ENCODED_OVERHEAD_BYTES +
                FilePayload.EncryptedChunk.ENCODED_OVERHEAD_BYTES + FileProtection.CHUNK_OVERHEAD_BYTES
        assertEquals(webRtc - framing, defaults.chunkSizeBytes)
        assertTrue(defaults.chunkSizeBytes in 240 * 1024..250 * 1024, "≈249 KiB, got ${defaults.chunkSizeBytes}")
    }

    @Test
    fun chunkSize_followsTheSmallerTransport() {
        val limits =
            FileLimits.from(TransportLimits(torMaxPayloadBytes = 100 * 1024, webRtcMaxPayloadBytes = 250 * 1024))
        assertEquals(defaults.chunkSizeBytes - 150 * 1024, limits.chunkSizeBytes)
    }

    @Test
    fun fetchFrame_isTheWebRtcFrameLessTheDataKind() {
        val webRtc = RuntimeConfig().webRtc.maxPayloadBytes
        assertEquals(webRtc - FileFetchFrame.Data.ENCODED_OVERHEAD_BYTES, defaults.fetchFrameBytes)
        // Tor never carries fetch frames.
        val smallTor = FileLimits.from(TransportLimits(torMaxPayloadBytes = 100 * 1024, webRtcMaxPayloadBytes = webRtc))
        assertEquals(defaults.fetchFrameBytes, smallTor.fetchFrameBytes)
    }

    @Test
    fun tinyTransport_failsInsteadOfClamping() {
        assertFailsWith<IllegalArgumentException> {
            FileLimits.from(TransportLimits(torMaxPayloadBytes = 1024, webRtcMaxPayloadBytes = 1024))
        }
    }
}
