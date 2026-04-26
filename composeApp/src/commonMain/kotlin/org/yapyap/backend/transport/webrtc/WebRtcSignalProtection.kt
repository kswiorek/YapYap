package org.yapyap.backend.transport.webrtc

import kotlin.random.Random
import kotlin.time.Clock
import org.yapyap.backend.directory.PeerDirectory
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.routing.EnvelopeObservability
import org.yapyap.backend.routing.FieldSensitivity
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

/**
 * Security boundary for signaling.
 *
 * Real implementations should sign/encrypt payloads before transport and verify/decrypt on receive.
 */
interface WebRtcSignalProtection {
    fun protect(signal: WebRtcSignal, createdAtEpochSeconds: Long, nonce: ByteArray): WebRtcSignalEnvelope

    fun open(envelope: WebRtcSignalEnvelope): WebRtcSignal
}

/**
 * Test/dev adapter that keeps signaling in plaintext.
 */
class PlaintextWebRtcSignalProtection : WebRtcSignalProtection {
    override fun protect(signal: WebRtcSignal, createdAtEpochSeconds: Long, nonce: ByteArray): WebRtcSignalEnvelope {
        val envelope = WebRtcSignalEnvelope(
            sessionId = signal.sessionId,
            kind = signal.kind,
            source = signal.source,
            target = signal.target,
            createdAtEpochSeconds = createdAtEpochSeconds,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            protectedPayload = signal.payload,
        )
        assertObservabilityContract(envelope)
        return envelope
    }

    override fun open(envelope: WebRtcSignalEnvelope): WebRtcSignal {
        assertObservabilityContract(envelope)
        return WebRtcSignal(
            sessionId = envelope.sessionId,
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = envelope.protectedPayload,
        )
    }

    private fun assertObservabilityContract(envelope: WebRtcSignalEnvelope) {
        val policy = EnvelopeObservability.webRtcSignalEnvelope.fields
        val unexpectedProtectedCleartext = envelope.observableHeaderValues()
            .keys
            .filter { policy[it] == FieldSensitivity.PROTECTED }
        require(unexpectedProtectedCleartext.isEmpty()) {
            "WebRTC signal envelope exposes protected fields in cleartext: $unexpectedProtectedCleartext"
        }
    }
}

data class WebRtcSignalProtectionContext(
    val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    val nonceGenerator: () -> ByteArray = { Random.Default.nextBytes(16) },
    val signalTtlSeconds: Long = 300,
    val peerDirectory: PeerDirectory,
)
