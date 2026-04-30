package org.yapyap.backend.protection

import org.yapyap.backend.crypto.SignatureProvider
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
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
    fun protect(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope

    fun open(envelope: WebRtcSignalEnvelope): WebRtcSignal
}

/**
 * Test/dev adapter that keeps signaling in plaintext.
 */
class PlaintextWebRtcSignalProtection(
    private val logger: AppLogger = NoopAppLogger,
) :
    BaseProtection<WebRtcSignal, WebRtcSignalEnvelope>(),
    WebRtcSignalProtection {
    override fun doProtect(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope {
        require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Context security scheme must be PLAINTEXT_TEST_ONLY for PlaintextWebRtcSignalProtection but got ${context.securityScheme}"
        }
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
        require(envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Expected PLAINTEXT_TEST_ONLY security scheme but got ${envelope.securityScheme}"
        }
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.SIGNAL_INBOUND_HANDLED,
            message = "Opened plaintext WebRTC signal envelope",
            fields = mapOf("sessionId" to envelope.sessionId, "kind" to envelope.kind.name),
        )
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
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<WebRtcSignal, WebRtcSignalEnvelope>(), WebRtcSignalProtection {
    override fun doProtect(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope {
        require(context.securityScheme == SignalSecurityScheme.SIGNED) {
            "Context security scheme must be SIGNED for SignedWebRtcSignalProtection but got ${context.securityScheme}"
        }
        val signingPayload = buildSigningPayload(
            envelopeId = input.sessionId,
            kindWireValue = input.kind.wireValue,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = context.nonce,
            protectedPayload = input.payload,
        )
        val signature = signatureProvider.signDetached(signingPayload)
        return WebRtcSignalEnvelope(
            sessionId = input.sessionId,
            kind = input.kind,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = context.nonce,
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
            "WebRTC signal signature verification failed for source=${envelope.source}"
        }
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.SIGNAL_INBOUND_HANDLED,
            message = "Verified signed WebRTC signal envelope",
            fields = mapOf("sessionId" to envelope.sessionId, "source" to envelope.source, "kind" to envelope.kind.name),
        )
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
