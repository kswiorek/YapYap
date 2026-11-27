package org.yapyap.wiring

import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.LargeFileFrame
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
    fun largeFileFrame_isTheWebRtcFrameLessTheDataKind() {
        val webRtc = RuntimeConfig().webRtc.maxPayloadBytes
        assertEquals(webRtc - LargeFileFrame.Data.ENCODED_OVERHEAD_BYTES, defaults.largeFileFrameBytes)
        // Tor never carries large-file frames.
        val smallTor = fileLimitsFor(TransportLimits(torMaxPayloadBytes = 100 * 1024, webRtcMaxPayloadBytes = webRtc))
        assertEquals(defaults.largeFileFrameBytes, smallTor.largeFileFrameBytes)
    }

    @Test
    fun maxSealBytes_isTheRoutableFrameLessTheOfferHeader() {
        val routable = minOf(RuntimeConfig().tor.maxPayloadBytes, RuntimeConfig().webRtc.maxPayloadBytes)
        assertEquals(
            BinaryEnvelope.maxContentBytes(routable, FileHeader.OFFER_ENCODED_BYTES),
            defaults.maxSealBytes,
        )
        assertTrue(defaults.maxSealBytes > 0, "the default transport leaves room for a seal")
    }

    @Test
    fun tinyTransport_failsInsteadOfClamping() {
        assertFailsWith<IllegalArgumentException> {
            fileLimitsFor(TransportLimits(torMaxPayloadBytes = 1024, webRtcMaxPayloadBytes = 1024))
        }
    }
}
