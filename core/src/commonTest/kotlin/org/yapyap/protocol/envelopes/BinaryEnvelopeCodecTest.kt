package org.yapyap.protocol.envelopes

import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class BinaryEnvelopeCodecTest {

    private val sampleId = Uuid.random()
    private val source = PeerId("devsourceaaaaaaaaaaaaaaaaaaaaaaaaaa.onion")
    private val target = PeerId("devtargetbbbbbbbbbbbbbbbbbbbbbbbbbb.onion")
    private val signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES) { it.toByte() }

    @Test
    fun encode_decode_roundTrip_emptyPayload() {
        val original = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.MESSAGE,
            dispositionRequested = true,
            createdAt = epochSeconds(100L),
            expiresAt = epochSeconds(200L),
            source = source,
            target = target,
            routingHeader = byteArrayOf(),
            content = byteArrayOf(),
            signature = signature,
        )
        val decoded = BinaryEnvelope.decode(original.encode())
        assertEquals(original.packetType, decoded.packetType)
        assertEquals(original.dispositionRequested, decoded.dispositionRequested)
        assertEquals(original.createdAt, decoded.createdAt)
        assertEquals(original.expiresAt, decoded.expiresAt)
        assertEquals(original.source, decoded.source)
        assertEquals(original.target, decoded.target)
        assertEquals(original.packetId, decoded.packetId)
        assertContentEquals(original.routingHeader, decoded.routingHeader)
        assertContentEquals(original.content, decoded.content)
        assertContentEquals(original.signature, decoded.signature)
    }

    @Test
    fun encode_decode_roundTrip_dispositionNotRequested() {
        val original = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.MESSAGE,
            dispositionRequested = false,
            createdAt = epochSeconds(100L),
            expiresAt = epochSeconds(200L),
            source = source,
            target = target,
            routingHeader = byteArrayOf(1, 2),
            content = byteArrayOf(),
            signature = signature,
        )
        val decoded = BinaryEnvelope.decode(original.encode())
        assertEquals(false, decoded.dispositionRequested)
    }

    @Test
    fun encode_decode_roundTrip_nonEmptyPayload() {
        val routingHeader = byteArrayOf(0x00, 0x7f)
        val content = byteArrayOf(0x00, 0x7f, 0xff.toByte())
        val original = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = epochSeconds(0L),
            expiresAt = epochSeconds(0L),
            source = source,
            target = target,
            routingHeader = routingHeader,
            content = content,
            signature = signature,
        )
        val decoded = BinaryEnvelope.decode(original.encode())
        assertContentEquals(routingHeader, decoded.routingHeader)
        assertContentEquals(content, decoded.content)
        assertEquals(PacketType.FILE, decoded.packetType)
    }

    @Test
    fun init_rejectsExpiresBeforeCreated() {
        assertFailsWith<IllegalArgumentException> {
            BinaryEnvelope(
                packetId = sampleId,
                packetType = PacketType.SYSTEM,
                dispositionRequested = false,
                createdAt = epochSeconds(10L),
                expiresAt = epochSeconds(9L),
                source = source,
                target = target,
                routingHeader = byteArrayOf(),
                content = byteArrayOf(),
                signature = signature,
            )
        }
    }

    @Test
    fun decode_rejectsBadMagic() {
        val good = BinaryEnvelope(
            packetId = sampleId,
            packetType = PacketType.SIGNAL,
            dispositionRequested = true,
            createdAt = epochSeconds(1L),
            expiresAt = epochSeconds(2L),
            source = source,
            target = target,
            routingHeader = byteArrayOf(1),
            content = byteArrayOf(1),
            signature = signature,
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
            packetType = PacketType.SYSTEM,
            dispositionRequested = false,
            createdAt = epochSeconds(1L),
            expiresAt = epochSeconds(1L),
            source = source,
            target = target,
            routingHeader = byteArrayOf(),
            content = byteArrayOf(),
            signature = signature,
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
            dispositionRequested = true,
            createdAt = epochSeconds(1L),
            expiresAt = epochSeconds(2L),
            source = source,
            target = target,
            routingHeader = byteArrayOf(1, 2, 3),
            content = byteArrayOf(1, 2, 3),
            signature = signature,
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
            dispositionRequested = true,
            createdAt = epochSeconds(1L),
            expiresAt = epochSeconds(2L),
            source = source,
            target = target,
            routingHeader = byteArrayOf(),
            content = byteArrayOf(),
            signature = signature,
        ).encode()
        val withTrail = good + byteArrayOf(0x00)
        assertFailsWith<IllegalArgumentException> {
            BinaryEnvelope.decode(withTrail)
        }
    }
}
