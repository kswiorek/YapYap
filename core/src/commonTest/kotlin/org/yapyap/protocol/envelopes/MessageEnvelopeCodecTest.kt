package org.yapyap.protocol.envelopes

import org.yapyap.persistence.db.MessagePayloadType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import kotlin.test.*

class MessageEnvelopeCodecTest {

    private val source = PeerId("src-device")
    private val target = PeerId("dst-device")
    private val authorDeviceId = PeerId("author-device")
    private val nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 3 }
    private val testSignature = byteArrayOf(0x01, 0x02, 0x03)

    @Test
    fun messagePayload_text_encodeDecode_roundTrip() {
        val original = MessagePayload.Text(
            messageId = "mid-1",
            roomId = "room-a",
            senderAccountId = "acct",
            authorDeviceId = authorDeviceId,
            prevId = "prev",
            lamportClock = 42L,
            createdAtEpochSeconds = 1_700_000_042L,
            text = "hello",
            authorSignature = testSignature,
        )
        val bytes = original.encode()
        val decoded = MessagePayload.Text.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun messagePayload_globalEvent_encodeDecode_roundTrip() {
        val original = MessagePayload.GlobalEvent(
            messageId = "evt-1",
            roomId = "GLOBAL",
            senderAccountId = "acct",
            authorDeviceId = authorDeviceId,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = 1_700_000_000L,
            eventBytes = byteArrayOf(0x01, 0x02),
            authorSignature = testSignature,
        )
        val bytes = original.encode()
        val decoded = MessagePayload.GlobalEvent.decode(bytes)
        assertGlobalEventPayloadEquals(original, decoded)
    }

    @Test
    fun messagePayload_encodeForAuthorSigning_excludesSignature() {
        val payload = MessagePayload.Text(
            messageId = "mid-sign",
            roomId = "room-a",
            senderAccountId = "acct",
            authorDeviceId = authorDeviceId,
            prevId = "prev",
            lamportClock = 42L,
            createdAtEpochSeconds = 1_700_000_042L,
            text = "hello",
            authorSignature = testSignature,
        )
        val signedBytes = payload.encodeForAuthorSigning()
        val fullBytes = payload.encode()
        assertTrue(signedBytes.size < fullBytes.size)
        assertFalse(signedBytes.contentEquals(testSignature))
    }

    @Test
    fun messageEnvelope_full_encodeDecode_globalEvent_roundTrip() {
        val payload = MessagePayload.GlobalEvent(
            messageId = "ge-full",
            roomId = "GLOBAL",
            senderAccountId = "acct-ge",
            authorDeviceId = authorDeviceId,
            prevId = "p",
            lamportClock = 99L,
            createdAtEpochSeconds = 1_700_000_099L,
            eventBytes = byteArrayOf(0xab.toByte()),
            authorSignature = testSignature,
        )
        val env = MessageEnvelope(
            messageId = "ge-full",
            source = source,
            target = target,
            createdAtEpochSeconds = 5L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = byteArrayOf(1, 2, 3),
            payload = payload.encode(),
        )
        val round = MessageEnvelope.decode(env.encode())
        assertMessageEnvelopeEquals(env, round)
    }

    @Test
    fun messageEnvelope_full_encodeDecode_text_roundTrip() {
        val payload = MessagePayload.Text(
            messageId = "mid-2",
            roomId = "room-b",
            senderAccountId = "acct2",
            authorDeviceId = authorDeviceId,
            prevId = null,
            lamportClock = 1L,
            createdAtEpochSeconds = 1_700_000_001L,
            text = "",
            authorSignature = testSignature,
        )
        val env = MessageEnvelope(
            messageId = "mid-2",
            source = source,
            target = target,
            createdAtEpochSeconds = 1_700_000_000L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = payload.encode(),
        )
        val round = MessageEnvelope.decode(env.encode())
        assertMessageEnvelopeEquals(env, round)
    }

    @Test
    fun messageEnvelope_encodeForSigning_omitsSignatureBytes() {
        val payload = MessagePayload.Text(
            messageId = "mid-sign",
            roomId = "r",
            senderAccountId = "a",
            authorDeviceId = authorDeviceId,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = 0L,
            text = "\u0009",
            authorSignature = testSignature,
        )
        val sig = ByteArray(64) { it.toByte() }
        val signed = MessageEnvelope(
            messageId = "mid-sign",
            source = source,
            target = target,
            createdAtEpochSeconds = 0L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = sig,
            payload = payload.encode(),
        )
        val unsigned = signed.copy(signature = null)
        assertContentEquals(unsigned.encode(), signed.encodeForSigning())
        assertMessageEnvelopeEquals(unsigned, MessageEnvelope.decode(signed.encodeForSigning()))
    }

    @Test
    fun messageEnvelope_full_encodeDecode_signedSignatureBytes_roundTrip() {
        val payload = MessagePayload.Text(
            messageId = "mid-3",
            roomId = "r",
            senderAccountId = "a",
            authorDeviceId = authorDeviceId,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = 0L,
            text = "\u0009",
            authorSignature = testSignature,
        )
        val sig = ByteArray(64) { it.toByte() }
        val env = MessageEnvelope(
            messageId = "mid-3",
            source = source,
            target = target,
            createdAtEpochSeconds = 0L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = sig,
            payload = payload.encode(),
        )
        val round = MessageEnvelope.decode(env.encode())
        assertMessageEnvelopeEquals(env, round)
    }

    @Test
    fun messageEnvelope_init_rejectsBlankMessageId() {
        assertFailsWith<IllegalArgumentException> {
            MessageEnvelope(
                messageId = " ",
                source = source,
                target = target,
                createdAtEpochSeconds = 0L,
                nonce = nonce,
                securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                signature = null,
                payload = MessagePayload.Text(
                    messageId = "x",
                    roomId = "r",
                    senderAccountId = "a",
                    authorDeviceId = authorDeviceId,
                    prevId = null,
                    lamportClock = 0L,
                    createdAtEpochSeconds = 0L,
                    text = "",
                    authorSignature = testSignature,
                ).encode(),
            )
        }
    }

    @Test
    fun messagePayloadType_enum_wireValuesDistinct() {
        val wires = MessagePayloadType.entries.map { it.wireValue }.toSet()
        assertEquals(MessagePayloadType.entries.size, wires.size)
    }

    @Test
    fun messagePayload_types_matchDiscriminant() {
        val text = MessagePayload.Text(
            messageId = "t",
            roomId = "r",
            senderAccountId = "a",
            authorDeviceId = authorDeviceId,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = 0L,
            text = "",
            authorSignature = testSignature,
        )
        assertEquals(MessagePayloadType.TEXT, text.payloadType)

        val ge = MessagePayload.GlobalEvent(
            messageId = "g",
            senderAccountId = "a",
            authorDeviceId = authorDeviceId,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = 0L,
            eventBytes = byteArrayOf(),
            authorSignature = testSignature,
        )
        assertEquals(MessagePayloadType.GLOBAL_EVENT, ge.payloadType)
    }

    private fun assertMessageEnvelopeEquals(expected: MessageEnvelope, actual: MessageEnvelope) {
        assertEquals(expected.messageId, actual.messageId)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.target, actual.target)
        assertEquals(expected.createdAtEpochSeconds, actual.createdAtEpochSeconds)
        assertContentEquals(expected.nonce, actual.nonce)
        assertEquals(expected.securityScheme, actual.securityScheme)
        when {
            expected.signature == null -> assertNull(actual.signature)
            else -> assertContentEquals(expected.signature, actual.signature!!)
        }
        assertMessagePayloadEquals(expected.decodePayload(), actual.decodePayload())
    }

    private fun assertMessagePayloadEquals(expected: MessagePayload, actual: MessagePayload) {
        when {
            expected is MessagePayload.Text && actual is MessagePayload.Text ->
                assertEquals(expected, actual)
            expected is MessagePayload.GlobalEvent && actual is MessagePayload.GlobalEvent ->
                assertGlobalEventPayloadEquals(expected, actual)
            else -> fail("Payload kinds differ: ${expected::class} vs ${actual::class}")
        }
    }

    private fun assertGlobalEventPayloadEquals(
        expected: MessagePayload.GlobalEvent,
        actual: MessagePayload.GlobalEvent,
    ) {
        assertEquals(expected.messageId, actual.messageId)
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.senderAccountId, actual.senderAccountId)
        assertEquals(expected.authorDeviceId, actual.authorDeviceId)
        assertEquals(expected.prevId, actual.prevId)
        assertEquals(expected.lamportClock, actual.lamportClock)
        assertEquals(expected.createdAtEpochSeconds, actual.createdAtEpochSeconds)
        assertContentEquals(expected.eventBytes, actual.eventBytes)
        assertContentEquals(expected.authorSignature, actual.authorSignature)
    }
}
