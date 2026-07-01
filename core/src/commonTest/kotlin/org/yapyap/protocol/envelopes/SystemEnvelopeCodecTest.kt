package org.yapyap.protocol.envelopes

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.packet.PacketType
import kotlin.test.*

class SystemEnvelopeCodecTest {

    private val source = PeerId("src-device")
    private val target = PeerId("dst-device")
    private val nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 3 }
    private val samplePacketId = PacketId.fromHex("01".repeat(PacketId.SIZE_BYTES))

    @Test
    fun systemPayload_packetAck_encodeDecode_roundTrip() {
        val original = SystemPayload.PacketAck(
            packetId = samplePacketId,
            packetType = PacketType.MESSAGE,
        )
        val decoded = SystemPayload.PacketAck.decode(original.encode())
        assertPacketAckEquals(original, decoded)
    }

    @Test
    fun systemPayload_packetNack_encodeDecode_roundTrip() {
        val original = SystemPayload.PacketNack(
            packetId = samplePacketId,
            packetType = PacketType.FILE,
            reason = PacketNackReason.EXPIRED,
            reasonText = "ttl exceeded",
        )
        val decoded = SystemPayload.PacketNack.decode(original.encode())
        assertPacketNackEquals(original, decoded)
    }

    @Test
    fun systemPayload_packetNack_encodeDecode_nullReasonText_roundTrip() {
        val original = SystemPayload.PacketNack(
            packetId = samplePacketId,
            packetType = PacketType.SIGNAL,
            reason = PacketNackReason.WRONG_TARGET,
            reasonText = null,
        )
        val decoded = SystemPayload.PacketNack.decode(original.encode())
        assertPacketNackEquals(original, decoded)
    }

    @Test
    fun systemEnvelope_full_encodeDecode_packetAck_roundTrip() {
        val payload = SystemPayload.PacketAck(
            packetId = samplePacketId,
            packetType = PacketType.SYSTEM,
        )
        val env = SystemEnvelope(
            correlationId = "ack:${samplePacketId.toHex()}",
            source = source,
            target = target,
            createdAtEpochSeconds = 1_700_000_000L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(64) { it.toByte() },
            payload = payload.encode(),
        )
        val round = SystemEnvelope.decode(env.encode())
        assertSystemEnvelopeEquals(env, round)
    }

    @Test
    fun systemEnvelope_full_encodeDecode_packetNack_roundTrip() {
        val payload = SystemPayload.PacketNack(
            packetId = samplePacketId,
            packetType = PacketType.MESSAGE,
            reason = PacketNackReason.DECODE_FAILED,
            reasonText = null,
        )
        val env = SystemEnvelope(
            correlationId = "nack:${samplePacketId.toHex()}",
            source = source,
            target = target,
            createdAtEpochSeconds = 42L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = payload.encode(),
        )
        val round = SystemEnvelope.decode(env.encode())
        assertSystemEnvelopeEquals(env, round)
    }

    @Test
    fun systemEnvelope_init_rejectsBlankCorrelationId() {
        assertFailsWith<IllegalArgumentException> {
            SystemEnvelope(
                correlationId = " ",
                source = source,
                target = target,
                createdAtEpochSeconds = 0L,
                nonce = nonce,
                securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                signature = null,
                payload = SystemPayload.PacketAck(
                    packetId = samplePacketId,
                    packetType = PacketType.MESSAGE,
                ).encode(),
            )
        }
    }

    @Test
    fun systemEnvelopeKind_enum_wireValuesDistinct() {
        val wires = SystemEnvelopeKind.entries.map { it.wireValue }.toSet()
        assertEquals(SystemEnvelopeKind.entries.size, wires.size)
    }

    @Test
    fun packetNackReason_fromWireValue_coversAll() {
        PacketNackReason.entries.forEach { reason ->
            assertEquals(reason, PacketNackReason.fromWireValue(reason.wireValue))
        }
    }

    private fun assertSystemEnvelopeEquals(expected: SystemEnvelope, actual: SystemEnvelope) {
        assertEquals(expected.correlationId, actual.correlationId)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.target, actual.target)
        assertEquals(expected.createdAtEpochSeconds, actual.createdAtEpochSeconds)
        assertContentEquals(expected.nonce, actual.nonce)
        assertEquals(expected.securityScheme, actual.securityScheme)
        when {
            expected.signature == null -> assertNull(actual.signature)
            else -> assertContentEquals(expected.signature, actual.signature!!)
        }
        assertSystemPayloadEquals(expected.decodePayload(), actual.decodePayload())
    }

    private fun assertSystemPayloadEquals(expected: SystemPayload, actual: SystemPayload) {
        when {
            expected is SystemPayload.PacketAck && actual is SystemPayload.PacketAck ->
                assertPacketAckEquals(expected, actual)
            expected is SystemPayload.PacketNack && actual is SystemPayload.PacketNack ->
                assertPacketNackEquals(expected, actual)
            else -> fail("Payload kinds differ: ${expected::class} vs ${actual::class}")
        }
    }

    private fun assertPacketAckEquals(expected: SystemPayload.PacketAck, actual: SystemPayload.PacketAck) {
        assertEquals(expected.packetId, actual.packetId)
        assertEquals(expected.packetType, actual.packetType)
    }

    private fun assertPacketNackEquals(expected: SystemPayload.PacketNack, actual: SystemPayload.PacketNack) {
        assertEquals(expected.packetId, actual.packetId)
        assertEquals(expected.packetType, actual.packetType)
        assertEquals(expected.reason, actual.reason)
        assertEquals(expected.reasonText, actual.reasonText)
    }
}
