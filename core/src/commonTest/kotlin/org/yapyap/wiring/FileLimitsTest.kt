package org.yapyap.wiring

import org.yapyap.routing.router.TransportLimits
import kotlin.test.Test
import kotlin.test.assertEquals

class FileLimitsTest {

    @Test
    fun chunkSize_defaultsTo220KiB() {
        val limits = FileLimits.from(
            TransportLimits(torMaxPayloadBytes = 4 * 1024 * 1024, webRtcMaxPayloadBytes = 250 * 1024),
        )
        assertEquals(220 * 1024, limits.chunkSizeBytes)
    }

    @Test
    fun chunkSize_followsSmallerTransport() {
        val limits = FileLimits.from(
            TransportLimits(torMaxPayloadBytes = 100 * 1024, webRtcMaxPayloadBytes = 250 * 1024),
        )
        assertEquals(100 * 1024 - FileLimits.CHUNK_RESERVE_BYTES, limits.chunkSizeBytes)
    }

    @Test
    fun chunkSize_neverBelowFloor() {
        val limits = FileLimits.from(TransportLimits(torMaxPayloadBytes = 1024, webRtcMaxPayloadBytes = 1024))
        assertEquals(FileLimits.MIN_CHUNK_SIZE_BYTES, limits.chunkSizeBytes)
    }
}
