package org.yapyap.protocol.envelopes

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DagPayloadType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

class MessageEnvelopeCodecTest {

    private val source = PeerId("src-device")
    private val target = PeerId("dst-device")
    private val authorDeviceId = PeerId("author-device")
    private val authorAccountId = AccountId("author-account")
    private val testSignature = byteArrayOf(0x01, 0x02, 0x03)

    @Test
    fun messagePayload_text_encodeDecode_roundTrip() {
        val original = DagPayload.Text(
            messageId = Uuid.random(),
            roomId = RoomId(Uuid.random()),
            senderAccountId = authorAccountId,
            authorDeviceId = authorDeviceId,
            prevIds = listOf(Uuid.random()),
            createdAt = epochSeconds(1_700_000_042L),
            text = "hello",
            authorSignature = testSignature,
        )
        val bytes = original.encode()
        val decoded = DagPayload.Text.decode(bytes)
        assertMessagePayloadEquals(original, decoded)
    }

    @Test
    fun messagePayload_globalEvent_encodeDecode_roundTrip() {
        val original = DagPayload.GlobalEvent(
            messageId = Uuid.random(),
            senderAccountId = authorAccountId,
            authorDeviceId = authorDeviceId,
            prevIds = emptyList(),
            createdAt = epochSeconds(1_700_000_000L),
            eventBytes = byteArrayOf(0x01, 0x02),
            authorSignature = testSignature,
        )
        val bytes = original.encode()
        val decoded = DagPayload.GlobalEvent.decode(bytes)
        assertGlobalEventPayloadEquals(original, decoded)
    }

    @Test
    fun messagePayload_encodeForAuthorSigning_excludesSignature() {
        val payload = DagPayload.Text(
            messageId = Uuid.random(),
            roomId = RoomId(Uuid.random()),
            senderAccountId = authorAccountId,
            authorDeviceId = authorDeviceId,
            prevIds = listOf(Uuid.random()),
            createdAt = epochSeconds(1_700_000_042L),
            text = "hello",
            authorSignature = testSignature,
        )
        val signedBytes = payload.encodeForAuthorSigning()
        val fullBytes = payload.encode()
        assertTrue(signedBytes.size < fullBytes.size)
        assertFalse(signedBytes.contentEquals(testSignature))
    }

    @Test
    fun messageHeader_encodeDecode_roundTrip() {
        val original = MessageHeader(origin = source, finalTarget = target)
        val decoded = MessageHeader.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(source, decoded.origin)
        assertEquals(target, decoded.finalTarget)
    }

    @Test
    fun messageHeader_observableValues_coverOriginAndTarget() {
        val header = MessageHeader(origin = source, finalTarget = target)
        val values = header.observableValues()
        assertEquals(source, values[MessageHeader.Companion.Fields.ORIGIN])
        assertEquals(target, values[MessageHeader.Companion.Fields.FINAL_TARGET])
    }

    @Test
    fun messageHeader_directAndRelayed_shareOneShape() {
        // Direct sends write origin == source and finalTarget == target, so direct and
        // relayed packets have one shape; a relay reads the same fields to admit and forward.
        val direct = MessageHeader(origin = source, finalTarget = target)
        val decoded = MessageHeader.decode(direct.encode())
        assertEquals(direct, decoded)
    }

    @Test
    fun messagePayloadType_enum_wireValuesDistinct() {
        val wires = DagPayloadType.entries.map { it.wireValue }.toSet()
        assertEquals(DagPayloadType.entries.size, wires.size)
    }

    @Test
    fun messagePayload_types_matchDiscriminant() {
        val text = DagPayload.Text(
            messageId = Uuid.random(),
            roomId = RoomId(Uuid.random()),
            senderAccountId = authorAccountId,
            authorDeviceId = authorDeviceId,
            prevIds = emptyList(),
            createdAt = epochSeconds(0L),
            text = "",
            authorSignature = testSignature,
        )
        assertEquals(DagPayloadType.TEXT, text.payloadType)

        val ge = DagPayload.GlobalEvent(
            messageId = Uuid.random(),
            senderAccountId = authorAccountId,
            authorDeviceId = authorDeviceId,
            prevIds = emptyList(),
            createdAt = epochSeconds(0L),
            eventBytes = byteArrayOf(),
            authorSignature = testSignature,
        )
        assertEquals(DagPayloadType.GLOBAL_EVENT, ge.payloadType)
    }

    private fun assertMessagePayloadEquals(expected: MessagePayload, actual: MessagePayload) {
        when {
            expected is DagPayload.Text && actual is DagPayload.Text ->
                assertTextPayloadEquals(expected, actual)
            expected is DagPayload.GlobalEvent && actual is DagPayload.GlobalEvent ->
                assertGlobalEventPayloadEquals(expected, actual)

            else -> fail("Payload kinds differ: ${expected::class} vs ${actual::class}")
        }
    }

    private fun assertTextPayloadEquals(expected: DagPayload.Text, actual: DagPayload.Text) {
        assertEquals(expected.messageId, actual.messageId)
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.senderAccountId, actual.senderAccountId)
        assertEquals(expected.authorDeviceId, actual.authorDeviceId)
        assertEquals(expected.prevIds, actual.prevIds)
        assertEquals(expected.createdAt, actual.createdAt)
        assertEquals(expected.text, actual.text)
        assertEquals(expected.attachments, actual.attachments)
        assertContentEquals(expected.authorSignature, actual.authorSignature)
    }

    private fun assertGlobalEventPayloadEquals(
        expected: DagPayload.GlobalEvent,
        actual: DagPayload.GlobalEvent,
    ) {
        assertEquals(expected.messageId, actual.messageId)
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.senderAccountId, actual.senderAccountId)
        assertEquals(expected.authorDeviceId, actual.authorDeviceId)
        assertEquals(expected.prevIds, actual.prevIds)
        assertEquals(expected.createdAt, actual.createdAt)
        assertContentEquals(expected.eventBytes, actual.eventBytes)
        assertContentEquals(expected.authorSignature, actual.authorSignature)
    }
}
