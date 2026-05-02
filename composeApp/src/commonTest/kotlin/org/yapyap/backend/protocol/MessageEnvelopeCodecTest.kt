package org.yapyap.backend.protocol

import org.yapyap.backend.db.MessageLifecycleState
import org.yapyap.backend.db.MessagePayloadType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

class MessageEnvelopeCodecTest {

    private val source = PeerId("src-device")
    private val target = PeerId("dst-device")
    private val nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 3 }

    @Test
    fun messagePayload_text_encodeDecode_roundTrip() {
        val original = MessagePayload.Text(
            messageId = "mid-1",
            roomId = "room-a",
            senderAccountId = "acct",
            prevId = "prev",
            lamportClock = 42L,
            messagePayload = "hello".encodeToByteArray(),
            lifecycleState = MessageLifecycleState.SENT,
            isOrphaned = false,
        )
        val bytes = original.encode()
        val decoded = MessagePayload.Text.decode(bytes)
        assertTextPayloadEquals(original, decoded)
    }

    @Test
    fun messagePayload_globalEvent_encodeDecode_roundTrip() {
        val original = MessagePayload.GlobalEvent(
            messageId = "evt-1",
            roomId = "GLOBAL",
            senderAccountId = "acct",
            prevId = null,
            lamportClock = 0L,
            eventPayload = byteArrayOf(0x01, 0x02),
            lifecycleState = MessageLifecycleState.CREATED,
            isOrphaned = true,
        )
        val bytes = original.encode()
        val decoded = MessagePayload.GlobalEvent.decode(bytes)
        assertGlobalEventPayloadEquals(original, decoded)
    }

    @Test
    fun messageEnvelope_full_encodeDecode_globalEvent_roundTrip() {
        val payload = MessagePayload.GlobalEvent(
            messageId = "ge-full",
            roomId = "GLOBAL",
            senderAccountId = "acct-ge",
            prevId = "p",
            lamportClock = 99L,
            eventPayload = byteArrayOf(0xab.toByte()),
            lifecycleState = MessageLifecycleState.ARCHIVED,
            isOrphaned = false,
        )
        val env = MessageEnvelope(
            messageId = "ge-full",
            source = source,
            target = target,
            createdAtEpochSeconds = 5L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = byteArrayOf(1, 2, 3),
            payload = payload,
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
            prevId = null,
            lamportClock = 1L,
            messagePayload = byteArrayOf(),
            lifecycleState = MessageLifecycleState.ACKED,
            isOrphaned = false,
        )
        val env = MessageEnvelope(
            messageId = "mid-2",
            source = source,
            target = target,
            createdAtEpochSeconds = 1_700_000_000L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = payload,
        )
        val round = MessageEnvelope.decode(env.encode())
        assertMessageEnvelopeEquals(env, round)
    }

    @Test
    fun messageEnvelope_full_encodeDecode_signedSignatureBytes_roundTrip() {
        val payload = MessagePayload.Text(
            messageId = "mid-3",
            roomId = "r",
            senderAccountId = "a",
            prevId = null,
            lamportClock = 0L,
            messagePayload = byteArrayOf(9),
            lifecycleState = MessageLifecycleState.CREATED,
            isOrphaned = false,
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
            payload = payload,
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
                    prevId = null,
                    lamportClock = 0L,
                    messagePayload = byteArrayOf(),
                    lifecycleState = MessageLifecycleState.CREATED,
                    isOrphaned = false,
                ),
            )
        }
    }

    @Test
    fun messageEnvelopeKind_enum_wireValuesDistinct() {
        val wires = MessageEnvelopeKind.entries.map { it.wireValue }.toSet()
        assertEquals(MessageEnvelopeKind.entries.size, wires.size)
    }

    @Test
    fun messagePayload_types_matchKinds() {
        val text = MessagePayload.Text(
            messageId = "t",
            roomId = "r",
            senderAccountId = "a",
            prevId = null,
            lamportClock = 0L,
            messagePayload = byteArrayOf(),
            lifecycleState = MessageLifecycleState.CREATED,
            isOrphaned = false,
        )
        assertEquals(MessageEnvelopeKind.TEXT, text.kind)
        assertEquals(MessagePayloadType.TEXT, text.payloadType)

        val ge = MessagePayload.GlobalEvent(
            messageId = "g",
            senderAccountId = "a",
            prevId = null,
            lamportClock = 0L,
            eventPayload = byteArrayOf(),
            lifecycleState = MessageLifecycleState.CREATED,
            isOrphaned = false,
        )
        assertEquals(MessageEnvelopeKind.GLOBAL_EVENT, ge.kind)
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
        assertMessagePayloadEquals(expected.payload, actual.payload)
    }

    private fun assertMessagePayloadEquals(expected: MessagePayload, actual: MessagePayload) {
        when {
            expected is MessagePayload.Text && actual is MessagePayload.Text ->
                assertTextPayloadEquals(expected, actual)
            expected is MessagePayload.GlobalEvent && actual is MessagePayload.GlobalEvent ->
                assertGlobalEventPayloadEquals(expected, actual)
            else -> fail("Payload kinds differ: ${expected::class} vs ${actual::class}")
        }
    }

    private fun assertTextPayloadEquals(expected: MessagePayload.Text, actual: MessagePayload.Text) {
        assertEquals(expected.messageId, actual.messageId)
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.senderAccountId, actual.senderAccountId)
        assertEquals(expected.prevId, actual.prevId)
        assertEquals(expected.lamportClock, actual.lamportClock)
        assertContentEquals(expected.messagePayload, actual.messagePayload)
        assertEquals(expected.lifecycleState, actual.lifecycleState)
        assertEquals(expected.isOrphaned, actual.isOrphaned)
    }

    private fun assertGlobalEventPayloadEquals(
        expected: MessagePayload.GlobalEvent,
        actual: MessagePayload.GlobalEvent,
    ) {
        assertEquals(expected.messageId, actual.messageId)
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.senderAccountId, actual.senderAccountId)
        assertEquals(expected.prevId, actual.prevId)
        assertEquals(expected.lamportClock, actual.lamportClock)
        assertContentEquals(expected.eventPayload, actual.eventPayload)
        assertEquals(expected.lifecycleState, actual.lifecycleState)
        assertEquals(expected.isOrphaned, actual.isOrphaned)
    }
}
