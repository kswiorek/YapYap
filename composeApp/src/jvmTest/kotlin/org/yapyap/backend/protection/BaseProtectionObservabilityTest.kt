package org.yapyap.backend.protection

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.routing.EnvelopeObservability
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
import org.yapyap.backend.transport.webrtc.types.WebRtcSignalKind

/**
 * If [BaseProtection.assertObservabilityContract] regresses and allows cleartext "protected" fields,
 * these tests fail — unless PROTECTED markers are removed from [EnvelopeObservability], which would itself be a bug.
 */
class BaseProtectionObservabilityTest {

    @Test
    fun protect_rejectsHeaderMapThatExposesMessagePayloadUnderProtectedPolicyKey() {
        val protection = LeakyPlaintextMessageProtection()
        val payload = sampleTextPayload()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        assertFailsWith<IllegalArgumentException> {
            protection.protect(payload, ctx)
        }
    }

    @Test
    fun open_rejectsSameLeakBeforeCryptographicWork() {
        val protection = LeakyPlaintextMessageProtection()
        val payload = sampleTextPayload()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        val envelope = protection.bypassProtectForTest(payload, ctx)
        assertFailsWith<IllegalArgumentException> {
            protection.open(envelope)
        }
    }

    @Test
    fun protect_rejectsHeaderMapThatExposesWebRtcProtectedPayloadUnderProtectedPolicyKey() {
        val protection = LeakyPlaintextWebRtcSignalProtection()
        val input = WebRtcSignal(
            sessionId = "obs-session",
            kind = WebRtcSignalKind.ICE,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
            payload = byteArrayOf(4, 5),
        )
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        assertFailsWith<IllegalArgumentException> {
            protection.protect(input, ctx)
        }
    }

    /**
     * Adds [MessageEnvelope.Companion.Fields.PAYLOAD] into the observable map while it remains PROTECTED in policy.
     */
    private class LeakyPlaintextMessageProtection : BaseProtection<MessagePayload, MessageEnvelope>() {

        override fun doProtect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
            require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY)
            val messageId = when (input) {
                is MessagePayload.Text -> input.messageId
                is MessagePayload.GlobalEvent -> input.messageId
            }
            return MessageEnvelope(
                messageId = messageId,
                source = context.sourceDeviceId,
                target = context.targetDeviceId,
                createdAtEpochSeconds = context.createdAtEpochSeconds,
                nonce = context.nonce,
                securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                signature = null,
                payload = input,
            )
        }

        override fun doOpen(envelope: MessageEnvelope): MessagePayload {
            require(envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY)
            return envelope.decodePayload()
        }

        override fun observableHeaderValues(envelope: MessageEnvelope): Map<String, Any?> =
            envelope.observableHeaderValues() +
                (MessageEnvelope.Companion.Fields.PAYLOAD to envelope.payload.encode())

        override fun observabilityPolicy() = EnvelopeObservability.messageEnvelope.fields

        override fun envelopeLabel(): String = "leaky message envelope (test)"

        fun bypassProtectForTest(
            input: MessagePayload,
            context: EnvelopeProtectContext,
        ): MessageEnvelope = doProtect(input, context)
    }

    private class LeakyPlaintextWebRtcSignalProtection : BaseProtection<WebRtcSignal, WebRtcSignalEnvelope>() {
        override fun doProtect(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope {
            require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY)
            return WebRtcSignalEnvelope(
                sessionId = input.sessionId,
                kind = input.kind,
                source = context.sourceDeviceId,
                target = context.targetDeviceId,
                createdAtEpochSeconds = context.createdAtEpochSeconds,
                nonce = context.nonce,
                securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                signature = null,
                protectedPayload = input.payload,
            )
        }

        override fun doOpen(envelope: WebRtcSignalEnvelope): WebRtcSignal {
            require(envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY)
            return WebRtcSignal(
                sessionId = envelope.sessionId,
                kind = envelope.kind,
                source = envelope.source,
                target = envelope.target,
                payload = envelope.protectedPayload,
            )
        }

        override fun observableHeaderValues(envelope: WebRtcSignalEnvelope): Map<String, Any?> =
            envelope.observableHeaderValues() +
                (WebRtcSignalEnvelope.Companion.Fields.PROTECTED_PAYLOAD to envelope.protectedPayload)

        override fun observabilityPolicy() = EnvelopeObservability.webRtcSignalEnvelope.fields

        override fun envelopeLabel(): String = "leaky WebRTC signal envelope (test)"
    }
}
