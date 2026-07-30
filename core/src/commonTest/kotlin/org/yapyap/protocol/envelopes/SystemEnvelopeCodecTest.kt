package org.yapyap.protocol.envelopes

import org.yapyap.persistence.messaging.MessageCursor
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.packet.PacketType
import kotlin.test.*
import kotlin.uuid.Uuid

class SystemEnvelopeCodecTest {

    private val source = PeerId("src-device")
    private val target = PeerId("dst-device")
    private val nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 3 }
    private val samplePacketId = Uuid.random()

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
            systemEnvelopeId = Uuid.random(),
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
            systemEnvelopeId = Uuid.random(),
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
    fun systemEnvelope_full_encodeDecode_gapSyncRequest_roundTrip() {
        val syncRequest = SystemPayload.SyncRequest.GapSyncRequest(
            roomId = "room-a",
            maxMessages = 100,
            missingPrevId = Uuid.random(),
            orphanedMessageId = Uuid.random(),
        )
        val env = SystemEnvelope(
            systemEnvelopeId = Uuid.random(),
            source = source,
            target = target,
            createdAtEpochSeconds = 42L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = syncRequest.encode(),
        )
        val round = SystemEnvelope.decode(env.encode())
        assertSystemEnvelopeEquals(env, round)
    }

    @Test
    fun systemEnvelope_full_encodeDecode_rangeSyncRequest_roundTrip() {
        val syncRequest = SystemPayload.SyncRequest.RangeSyncRequest(
            roomId = "room-a",
            maxMessages = 100,
            sinceCursor = MessageCursor(
                1234567890L,
                 1234567890L,
                Uuid.random()
            )
        )
        val env = SystemEnvelope(
            systemEnvelopeId = Uuid.random(),
            source = source,
            target = target,
            createdAtEpochSeconds = 42L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = syncRequest.encode(),
        )
        val round = SystemEnvelope.decode(env.encode())
        assertSystemEnvelopeEquals(env, round)
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
        assertEquals(expected.systemEnvelopeId, actual.systemEnvelopeId)
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
            expected is SystemPayload.SyncRequest && actual is SystemPayload.SyncRequest ->
                assertSyncRequestEquals(expected, actual)
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
    private fun assertSyncRequestEquals(expected: SystemPayload.SyncRequest, actual: SystemPayload.SyncRequest) {
        when {
            expected is SystemPayload.SyncRequest.GapSyncRequest && actual is SystemPayload.SyncRequest.GapSyncRequest ->
                assertGapSyncRequestEquals(expected, actual)
            expected is SystemPayload.SyncRequest.RangeSyncRequest && actual is SystemPayload.SyncRequest.RangeSyncRequest ->
                assertRangeSyncRequestEquals(expected, actual)
            else -> fail("SyncRequest kinds differ: ${expected::class} vs ${actual::class}")
        }
    }
    private fun assertGapSyncRequestEquals(expected: SystemPayload.SyncRequest.GapSyncRequest, actual: SystemPayload.SyncRequest.GapSyncRequest) {
        assertEquals(expected.maxMessages, actual.maxMessages)
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.missingPrevId, actual.missingPrevId)
        assertEquals(expected.orphanedMessageId, actual.orphanedMessageId)
    }
    private fun assertRangeSyncRequestEquals(expected: SystemPayload.SyncRequest.RangeSyncRequest, actual: SystemPayload.SyncRequest.RangeSyncRequest) {
        assertEquals(expected.maxMessages, actual.maxMessages)
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.sinceCursor, actual.sinceCursor)
    }
}
