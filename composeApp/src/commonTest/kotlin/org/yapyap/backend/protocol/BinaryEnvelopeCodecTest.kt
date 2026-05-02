package org.yapyap.backend.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryEnvelopeCodecTest {

    private val sampleId = PacketId.fromHex("01".repeat(PacketId.SIZE_BYTES))
    private val source = PeerId("devsourceaaaaaaaaaaaaaaaaaaaaaaaaaa.onion")
    private val target = PeerId("devtargetbbbbbbbbbbbbbbbbbbbbbbbbbb.onion")

    @Test
    fun encode_decode_roundTrip_emptyPayload() {
        val original = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = 100L,
            expiresAtEpochSeconds = 200L,
            source = source,
            target = target,
            payload = byteArrayOf(),
        )
        val decoded = BinaryEnvelope.decode(original.encode())
        assertEquals(original.packetType, decoded.packetType)
        assertEquals(original.createdAtEpochSeconds, decoded.createdAtEpochSeconds)
        assertEquals(original.expiresAtEpochSeconds, decoded.expiresAtEpochSeconds)
        assertEquals(original.source, decoded.source)
        assertEquals(original.target, decoded.target)
        assertEquals(original.packetId, decoded.packetId)
        assertContentEquals(original.payload, decoded.payload)
    }

    @Test
    fun encode_decode_roundTrip_nonEmptyPayload() {
        val payload = byteArrayOf(0x00, 0x7f, 0xff.toByte())
        val original = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.FILE,
            createdAtEpochSeconds = 0L,
            expiresAtEpochSeconds = 0L,
            source = source,
            target = target,
            payload = payload,
        )
        val decoded = BinaryEnvelope.decode(original.encode())
        assertContentEquals(payload, decoded.payload)
        assertEquals(PacketType.FILE, decoded.packetType)
    }

    @Test
    fun init_rejectsExpiresBeforeCreated() {
        assertFailsWith<IllegalArgumentException> {
            BinaryEnvelope(
                packetId = sampleId,
                packetType = PacketType.ACK,
                createdAtEpochSeconds = 10L,
                expiresAtEpochSeconds = 9L,
                source = source,
                target = target,
                payload = byteArrayOf(),
            )
        }
    }

    @Test
    fun decode_rejectsBadMagic() {
        val good = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.SIGNAL,
            createdAtEpochSeconds = 1L,
            expiresAtEpochSeconds = 2L,
            source = source,
            target = target,
            payload = byteArrayOf(1),
        ).encode()
        val bad = good.copyOf()
        bad[0] = 0x00
        assertFailsWith<IllegalArgumentException> {
            BinaryEnvelope.decode(bad)
        }
    }

    @Test
    fun decode_rejectsUnsupportedVersion() {
        val good = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.DISCOVERY,
            createdAtEpochSeconds = 1L,
            expiresAtEpochSeconds = 1L,
            source = source,
            target = target,
            payload = byteArrayOf(),
        ).encode()
        val tampered = good.copyOf()
        tampered[4] = 2
        assertFailsWith<IllegalArgumentException> {
            BinaryEnvelope.decode(tampered)
        }
    }

    @Test
    fun decode_rejectsTruncatedBuffer() {
        val good = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = 1L,
            expiresAtEpochSeconds = 2L,
            source = source,
            target = target,
            payload = byteArrayOf(1, 2, 3),
        ).encode()
        assertFailsWith<IllegalArgumentException> {
            BinaryEnvelope.decode(good.copyOfRange(0, good.size - 1))
        }
    }

    @Test
    fun decode_rejectsTrailingBytes() {
        val good = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = 1L,
            expiresAtEpochSeconds = 2L,
            source = source,
            target = target,
            payload = byteArrayOf(),
        ).encode()
        val withTrail = good + byteArrayOf(0x00)
        assertFailsWith<IllegalArgumentException> {
            BinaryEnvelope.decode(withTrail)
        }
    }
}
