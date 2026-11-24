package org.yapyap.protocol.envelopes

import org.yapyap.protocol.PacketType
import org.yapyap.protocol.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

class SystemEnvelopeCodecTest {

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
        // Sync requests travel as sealed MESSAGE-family content now (envelope redesign §4),
        // not as SYSTEM. The codec itself is unchanged; the family moved.
        val original = SyncPayload.Request(
            roomId = RoomId(Uuid.random()),
            syncId = Uuid.random(),
            missingIds = listOf(Uuid.random(), Uuid.random()),
            knownIds = listOf(Uuid.random()),
        )
        val decoded = SyncPayload.Request.decode(original.encode())
        assertSyncRequestEquals(original, decoded)
    }

    @Test
    fun systemPayload_ping_probe_encodeDecode_roundTrip() {
        val original = SystemPayload.Ping(
            pingId = samplePacketId,
            isReply = false,
            selfReportedAvailability = 0.2,
            roomFrontiers = listOf(
                RoomId(Uuid.random()) to listOf(Uuid.random(), Uuid.random()),
                RoomId(Uuid.random()) to listOf(Uuid.random()),
            ),
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
            selfReportedAvailability = 0.8,
            roomFrontiers = emptyList(),
        )
        val decoded = SystemPayload.Ping.decode(original.encode())
        assertPingEquals(original, decoded)
        assertTrue(decoded.isReply, "reply must round-trip as a reply")
    }

    @Test
    fun systemPayload_ping_emptyFrontiers_encodeDecode_roundTrip() {
        val original = SystemPayload.Ping(
            pingId = samplePacketId,
            isReply = false,
            selfReportedAvailability = 1.0,
            roomFrontiers = emptyList(),
        )
        val decoded = SystemPayload.Ping.decode(original.encode())
        assertPingEquals(original, decoded)
    }

    @Test
    fun systemPayload_typingIndicator_encodeDecode_roundTrip() {
        val original = SystemPayload.TypingIndicator(
            roomId = RoomId(Uuid.random()),
            intervalMillis = 3_000,
        )
        val decoded = SystemPayload.TypingIndicator.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun systemPayload_logOff_encodeDecode_roundTrip() {
        val decoded = SystemPayload.LogOff.decode(SystemPayload.LogOff.encode())
        assertEquals(SystemPayload.LogOff, decoded)
    }

    @Test
    fun systemPayload_decode_dispatchesAllKinds() {
        val ack = SystemPayload.PacketAck(samplePacketId, PacketType.MESSAGE).encode()
        assertTrue(SystemPayload.decode(ack) is SystemPayload.PacketAck)
        val nack = SystemPayload.PacketNack(samplePacketId, PacketType.MESSAGE, PacketNackReason.EXPIRED, null).encode()
        assertTrue(SystemPayload.decode(nack) is SystemPayload.PacketNack)
        val typing = SystemPayload.TypingIndicator(RoomId(Uuid.random()), 1000).encode()
        assertTrue(SystemPayload.decode(typing) is SystemPayload.TypingIndicator)
        val ping = SystemPayload.Ping(samplePacketId, false, 0.5, emptyList()).encode()
        assertTrue(SystemPayload.decode(ping) is SystemPayload.Ping)
        val logOff = SystemPayload.LogOff.encode()
        assertTrue(SystemPayload.decode(logOff) is SystemPayload.LogOff)
    }

    @Test
    fun systemEnvelopeKind_enum_wireValuesDistinct() {
        val wires = SystemEnvelopeKind.entries.map { it.wireValue }.toSet()
        assertEquals(SystemEnvelopeKind.entries.size, wires.size)
    }

    @Test
    fun systemPayload_packetNack_decode_unknownReasonByte_yieldsNullReason() {
        val encoded = SystemPayload.PacketNack(
            packetId = samplePacketId,
            packetType = PacketType.MESSAGE,
            reason = PacketNackReason.EXPIRED,
            reasonText = null,
        ).encode().copyOf()
        // layout: kind(1) + packetId(16) + packetType(1) + reason(1) + nullableString
        encoded[18] = 127
        val decoded = SystemPayload.PacketNack.decode(encoded)
        assertEquals(samplePacketId, decoded.packetId)
        assertEquals(PacketType.MESSAGE, decoded.packetType)
        assertNull(decoded.reason)
        assertNull(decoded.reasonText)
    }

    @Test
    fun packetNackReason_fromWireValue_coversAll() {
        PacketNackReason.entries.forEach { reason ->
            assertEquals(reason, PacketNackReason.fromWireValue(reason.wireValue))
        }
    }

    private fun assertSystemPayloadEquals(expected: SystemPayload, actual: SystemPayload) {
        when {
            expected is SystemPayload.PacketAck && actual is SystemPayload.PacketAck ->
                assertPacketAckEquals(expected, actual)

            expected is SystemPayload.PacketNack && actual is SystemPayload.PacketNack ->
                assertPacketNackEquals(expected, actual)

            expected is SystemPayload.Ping && actual is SystemPayload.Ping ->
                assertPingEquals(expected, actual)

            expected is SystemPayload.TypingIndicator && actual is SystemPayload.TypingIndicator ->
                assertEquals(expected, actual)

            expected is SystemPayload.LogOff && actual is SystemPayload.LogOff -> Unit

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

    private fun assertSyncRequestEquals(expected: SyncPayload.Request, actual: SyncPayload.Request) {
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.syncId, actual.syncId)
        assertEquals(expected.missingIds, actual.missingIds)
        assertEquals(expected.knownIds, actual.knownIds)
    }

    private fun assertPingEquals(expected: SystemPayload.Ping, actual: SystemPayload.Ping) {
        assertEquals(expected.pingId, actual.pingId)
        assertEquals(expected.isReply, actual.isReply)
        assertEquals(expected.roomFrontiers, actual.roomFrontiers)
        // The wire value is quantised to a byte (1/255 granularity).
        assertEquals(
            expected.selfReportedAvailability,
            actual.selfReportedAvailability,
            absoluteTolerance = 1.0 / 255.0
        )
    }
}
