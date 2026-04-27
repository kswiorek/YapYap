package org.yapyap.backend.protection

import org.yapyap.backend.crypto.SignatureProvider
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.routing.EnvelopeObservability
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

/**
 * Security boundary for signaling.
 *
 * Real implementations should sign/encrypt payloads before transport and verify/decrypt on receive.
 */
interface WebRtcSignalProtection {
    fun protect(input: WebRtcSignal, createdAtEpochSeconds: Long, nonce: ByteArray): WebRtcSignalEnvelope

    fun open(envelope: WebRtcSignalEnvelope): WebRtcSignal
}

/**
 * Test/dev adapter that keeps signaling in plaintext.
 */
class PlaintextWebRtcSignalProtection :
    BaseProtection<WebRtcSignal, WebRtcSignalEnvelope>(),
    WebRtcSignalProtection {
    override fun doProtect(input: WebRtcSignal, createdAtEpochSeconds: Long, nonce: ByteArray): WebRtcSignalEnvelope {
        return WebRtcSignalEnvelope(
            sessionId = input.sessionId,
            kind = input.kind,
            source = input.source,
            target = input.target,
            createdAtEpochSeconds = createdAtEpochSeconds,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            protectedPayload = input.payload,
        )
    }

    override fun doOpen(envelope: WebRtcSignalEnvelope): WebRtcSignal {
        require(envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Expected PLAINTEXT_TEST_ONLY security scheme but got ${envelope.securityScheme}"
        }
        return WebRtcSignal(
            sessionId = envelope.sessionId,
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = envelope.protectedPayload,
        )
    }

    override fun observableHeaderValues(envelope: WebRtcSignalEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.webRtcSignalEnvelope.fields

    override fun envelopeLabel(): String = "WebRTC signal envelope"
}

class SignedWebRtcSignalProtection(
    private val signatureProvider: SignatureProvider,
) : BaseProtection<WebRtcSignal, WebRtcSignalEnvelope>(), WebRtcSignalProtection {
    override fun doProtect(input: WebRtcSignal, createdAtEpochSeconds: Long, nonce: ByteArray): WebRtcSignalEnvelope {
        val signingPayload = buildSigningPayload(
            envelopeId = input.sessionId,
            kindWireValue = input.kind.wireValue,
            source = input.source,
            target = input.target,
            createdAtEpochSeconds = createdAtEpochSeconds,
            nonce = nonce,
            protectedPayload = input.payload,
        )
        val signature = signatureProvider.signDetached(signingPayload)
        return WebRtcSignalEnvelope(
            sessionId = input.sessionId,
            kind = input.kind,
            source = input.source,
            target = input.target,
            createdAtEpochSeconds = createdAtEpochSeconds,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = signature,
            protectedPayload = input.payload,
        )
    }

    override fun doOpen(envelope: WebRtcSignalEnvelope): WebRtcSignal {
        require(envelope.securityScheme == SignalSecurityScheme.SIGNED) {
            "Expected SIGNED security scheme but got ${envelope.securityScheme}"
        }
        val signature = envelope.signature ?: error("SIGNED signal envelope must contain signature")
        val signingPayload = buildSigningPayload(
            envelopeId = envelope.sessionId,
            kindWireValue = envelope.kind.wireValue,
            source = envelope.source,
            target = envelope.target,
            createdAtEpochSeconds = envelope.createdAtEpochSeconds,
            nonce = envelope.nonce,
            protectedPayload = envelope.protectedPayload,
        )
        require(signatureProvider.verifyDetached(envelope.source, signingPayload, signature)) {
            "WebRTC signal signature verification failed for source=${envelope.source.deviceId}"
        }
        return WebRtcSignal(
            sessionId = envelope.sessionId,
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = envelope.protectedPayload,
        )
    }

    override fun observableHeaderValues(envelope: WebRtcSignalEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.webRtcSignalEnvelope.fields

    override fun envelopeLabel(): String = "WebRTC signal envelope"
}
