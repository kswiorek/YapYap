package org.yapyap.protocol.envelopes

import org.yapyap.orchestrator.dag.RoomId
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
    fun systemPayload_syncRequest_encodeDecode_roundTrip() {
        val original = SystemPayload.SyncRequest(
            roomId = RoomId(Uuid.random()),
            syncId = Uuid.random(),
            anchorLamport = 42L,
            orphanLamport = 7L,
        )
        val decoded = SystemPayload.SyncRequest.decode(original.encode())
        assertSyncRequestEquals(original, decoded)
    }

    @Test
    fun systemPayload_syncNack_encodeDecode_roundTrip() {
        val original = SystemPayload.SyncNack(
            syncId = Uuid.random(),
            reason = "ancestor not found",
        )
        val decoded = SystemPayload.SyncNack.decode(original.encode())
        assertSyncNackEquals(original, decoded)
    }

    @Test
    fun systemPayload_ping_probe_encodeDecode_roundTrip() {
        val original = SystemPayload.Ping(
            pingId = samplePacketId,
            isReply = false,
            roomLamports = listOf(RoomId(Uuid.random()) to 7L, RoomId(Uuid.random()) to 42L),
        )
        val decoded = SystemPayload.Ping.decode(original.encode())
        assertPingEquals(original, decoded)
        assertFalse(decoded.isReply, "probe must round-trip as a non-reply")
    }

    @Test
    fun systemPayload_ping_reply_encodeDecode_roundTrip() {
        val original = SystemPayload.Ping(
            pingId = samplePacketId,
            isReply = true,
            roomLamports = emptyList(),
        )
        val decoded = SystemPayload.Ping.decode(original.encode())
        assertPingEquals(original, decoded)
        assertTrue(decoded.isReply, "reply must round-trip as a reply")
    }

    @Test
    fun systemPayload_ping_emptyLamports_encodeDecode_roundTrip() {
        val original = SystemPayload.Ping(pingId = samplePacketId, isReply = false, roomLamports = emptyList())
        val decoded = SystemPayload.Ping.decode(original.encode())
        assertPingEquals(original, decoded)
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
    fun systemEnvelope_full_encodeDecode_syncRequest_roundTrip() {
        val syncRequest = SystemPayload.SyncRequest(
            roomId = RoomId(Uuid.random()),
            syncId = Uuid.random(),
            anchorLamport = 1234L,
            orphanLamport = 10L,
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
    fun systemEnvelope_full_encodeDecode_syncNack_roundTrip() {
        val syncNack = SystemPayload.SyncNack(
            syncId = Uuid.random(),
            reason = "request expired",
        )
        val env = SystemEnvelope(
            systemEnvelopeId = Uuid.random(),
            source = source,
            target = target,
            createdAtEpochSeconds = 42L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = syncNack.encode(),
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
            expected is SystemPayload.SyncNack && actual is SystemPayload.SyncNack ->
                assertSyncNackEquals(expected, actual)
            expected is SystemPayload.Ping && actual is SystemPayload.Ping ->
                assertPingEquals(expected, actual)
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
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.syncId, actual.syncId)
        assertEquals(expected.anchorLamport, actual.anchorLamport)
        assertEquals(expected.orphanLamport, actual.orphanLamport)
    }

    private fun assertSyncNackEquals(expected: SystemPayload.SyncNack, actual: SystemPayload.SyncNack) {
        assertEquals(expected.syncId, actual.syncId)
        assertEquals(expected.reason, actual.reason)
    }

    private fun assertPingEquals(expected: SystemPayload.Ping, actual: SystemPayload.Ping) {
        assertEquals(expected.pingId, actual.pingId)
        assertEquals(expected.isReply, actual.isReply)
        assertEquals(expected.roomLamports, actual.roomLamports)
    }
}
