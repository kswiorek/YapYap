package org.yapyap.backend.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryEnvelopeTest {

    @Test
    fun encodeDecodeRoundTripPreservesFields() {
        val packetId = PacketId.fromHex("00112233445566778899aabbccddeeff")
        val payload = "encrypted-payload".encodeToByteArray()
        val original = BinaryEnvelope(
            packetId = packetId,
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = 1_700_000_000L,
            expiresAtEpochSeconds = 1_700_000_900L,
            hopCount = 2,
            route = EnvelopeRoute(
                destinationAccount = "alice",
                destinationDevice = "phone-a",
                nextHopDevice = "relay-1",
            ),
            payload = payload,
        )

        val decoded = BinaryEnvelope.decode(original.encode())

        assertEquals(original.packetId, decoded.packetId)
        assertEquals(original.packetType, decoded.packetType)
        assertEquals(original.createdAtEpochSeconds, decoded.createdAtEpochSeconds)
        assertEquals(original.expiresAtEpochSeconds, decoded.expiresAtEpochSeconds)
        assertEquals(original.hopCount, decoded.hopCount)
        assertEquals(original.route, decoded.route)
        assertContentEquals(original.payload, decoded.payload)
    }

    @Test
    fun decodeFailsForInvalidMagic() {
        val invalid = ByteArray(16)
        assertFailsWith<IllegalArgumentException> {
            BinaryEnvelope.decode(invalid)
        }
    }
}

