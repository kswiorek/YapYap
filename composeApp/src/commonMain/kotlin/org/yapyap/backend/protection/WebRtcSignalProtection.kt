package org.yapyap.backend.protection

import kotlin.random.Random
import kotlin.time.Clock
import org.yapyap.backend.protocol.DeviceAddress
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.routing.EnvelopeObservability
import org.yapyap.backend.routing.FieldSensitivity
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
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

class SignedWebRtcSignalProtection(
    private val resolveLocalSigningKeyId: () -> String,
    private val signDetached: (keyId: String, message: ByteArray) -> ByteArray,
    private val verifyDetached: (source: DeviceAddress, message: ByteArray, signature: ByteArray) -> Boolean,
) : WebRtcSignalProtection {
    override fun protect(signal: WebRtcSignal, createdAtEpochSeconds: Long, nonce: ByteArray): WebRtcSignalEnvelope {
        val signingPayload = buildSigningPayload(
            sessionId = signal.sessionId,
            kindWireValue = signal.kind.wireValue,
            source = signal.source,
            target = signal.target,
            createdAtEpochSeconds = createdAtEpochSeconds,
            nonce = nonce,
            protectedPayload = signal.payload,
        )
        val signature = signDetached(resolveLocalSigningKeyId(), signingPayload)
        return WebRtcSignalEnvelope(
            sessionId = signal.sessionId,
            kind = signal.kind,
            source = signal.source,
            target = signal.target,
            createdAtEpochSeconds = createdAtEpochSeconds,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = signature,
            protectedPayload = signal.payload,
        )
    }

    override fun open(envelope: WebRtcSignalEnvelope): WebRtcSignal {
        val signature = envelope.signature ?: error("SIGNED signal envelope must contain signature")
        val signingPayload = buildSigningPayload(
            sessionId = envelope.sessionId,
            kindWireValue = envelope.kind.wireValue,
            source = envelope.source,
            target = envelope.target,
            createdAtEpochSeconds = envelope.createdAtEpochSeconds,
            nonce = envelope.nonce,
            protectedPayload = envelope.protectedPayload,
        )
        require(verifyDetached(envelope.source, signingPayload, signature)) {
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
}

data class WebRtcSignalProtectionContext(
    val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    val nonceGenerator: () -> ByteArray = { Random.nextBytes(16) },
    val signalTtlSeconds: Long = 300,
    val resolveAccountIdForDevice: (String) -> String,
    val resolveTorEndpointForDevice: (String) -> TorEndpoint,
    val resolveLocalSigningKeyId: () -> String = { "signing-key-unset" },
    val signDetached: (keyId: String, message: ByteArray) -> ByteArray = { _, _ -> ByteArray(0) },
    val verifyDetached: (source: DeviceAddress, message: ByteArray, signature: ByteArray) -> Boolean =
        { _, _, _ -> true },
)

private fun buildSigningPayload(
    sessionId: String,
    kindWireValue: Byte,
    source: DeviceAddress,
    target: DeviceAddress,
    createdAtEpochSeconds: Long,
    nonce: ByteArray,
    protectedPayload: ByteArray,
): ByteArray {
    val sourceBytes = "${source.accountId}/${source.deviceId}".encodeToByteArray()
    val targetBytes = "${target.accountId}/${target.deviceId}".encodeToByteArray()
    val headerBytes = buildString {
        append(sessionId)
        append('|')
        append(kindWireValue.toInt())
        append('|')
        append(createdAtEpochSeconds)
        append('|')
    }.encodeToByteArray()
    val result = ByteArray(
        headerBytes.size + sourceBytes.size + targetBytes.size + nonce.size + protectedPayload.size + 2
    )
    var offset = 0
    headerBytes.copyInto(result, destinationOffset = offset)
    offset += headerBytes.size
    sourceBytes.copyInto(result, destinationOffset = offset)
    offset += sourceBytes.size
    result[offset++] = '|'.code.toByte()
    targetBytes.copyInto(result, destinationOffset = offset)
    offset += targetBytes.size
    result[offset++] = '|'.code.toByte()
    nonce.copyInto(result, destinationOffset = offset)
    offset += nonce.size
    protectedPayload.copyInto(result, destinationOffset = offset)
    return result
}
