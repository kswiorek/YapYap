package org.yapyap.backend.transport.webrtc

import org.yapyap.backend.protocol.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebRtcSignalEnvelopeTest {

    @Test
    fun roundTripsEncodedEnvelope() {
        val envelope = WebRtcSignalEnvelope(
            sessionId = "session-01",
            kind = WebRtcSignalKind.OFFER,
            source = PeerId(accountName = "alice", deviceId = "alice-phone"),
            target = PeerId(accountName = "bob", deviceId = "bob-pi"),
            createdAtEpochSeconds = 1_700_000_100L,
            nonce = byteArrayOf(1, 2, 3, 4),
            securityScheme = WebRtcSignalSecurityScheme.SIGNED,
            signature = byteArrayOf(9, 8, 7),
            protectedPayload = "sdp-offer".encodeToByteArray(),
        )

        val decoded = WebRtcSignalEnvelope.decode(envelope.encode())

        assertEquals(envelope.sessionId, decoded.sessionId)
        assertEquals(envelope.kind, decoded.kind)
        assertEquals(envelope.source, decoded.source)
        assertEquals(envelope.target, decoded.target)
        assertEquals(envelope.createdAtEpochSeconds, decoded.createdAtEpochSeconds)
        assertContentEquals(envelope.nonce, decoded.nonce)
        assertEquals(envelope.securityScheme, decoded.securityScheme)
        assertContentEquals(envelope.signature, decoded.signature)
        assertContentEquals(envelope.protectedPayload, decoded.protectedPayload)
    }

    @Test
    fun rejectsInvalidMagic() {
        val bad = byteArrayOf(0, 1, 2, 3, 4)
        assertFailsWith<IllegalArgumentException> {
            WebRtcSignalEnvelope.decode(bad)
        }
    }
}

