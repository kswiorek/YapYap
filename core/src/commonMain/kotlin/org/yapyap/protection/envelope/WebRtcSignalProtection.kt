package org.yapyap.protection.envelope

import org.yapyap.crypto.e2ee.CryptoSessionManager
import org.yapyap.crypto.e2ee.CryptoWireLimits
import org.yapyap.crypto.e2ee.SessionWireFrame
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.protection.AuthenticationReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.EnvelopeObservability
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.transport.webrtc.types.WebRtcSignal

/**
 * Security boundary for signaling.
 *
 * Real implementations should sign/encrypt payloads before transport and verify/decrypt on receive.
 */
interface WebRtcSignalProtection {
    suspend fun protect(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope

    suspend fun open(envelope: WebRtcSignalEnvelope): WebRtcSignal
}

/**
 * Test/dev adapter that keeps signaling in plaintext.
 */
class PlaintextWebRtcSignalProtection(
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) :
    BaseProtection<WebRtcSignal, WebRtcSignalEnvelope>(),
    WebRtcSignalProtection {
    override suspend fun doProtect(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope {
        require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Context security scheme must be PLAINTEXT_TEST_ONLY for PlaintextWebRtcSignalProtection but got ${context.securityScheme}"
        }
        return WebRtcSignalEnvelope(
            sessionId = input.sessionId,
            kind = input.kind,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.PLAINTEXT_TEST_ONLY),
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.payload,
        )
    }

    override suspend fun doOpen(envelope: WebRtcSignalEnvelope): WebRtcSignal {
        require(envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Expected PLAINTEXT_TEST_ONLY security scheme but got ${envelope.securityScheme}"
        }
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Opened plaintext WebRTC signal envelope",
            fields = mapOf("sessionId" to envelope.sessionId, "kind" to envelope.kind.name),
        )
        return WebRtcSignal(
            sessionId = envelope.sessionId,
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = envelope.payload,
        )
    }

    override fun observableHeaderValues(envelope: WebRtcSignalEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.webRtcSignalEnvelope.fields

    override fun envelopeLabel(): String = "WebRTC signal envelope"
}

class SignedWebRtcSignalProtection(
    private val signatureProvider: SignatureProvider,
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<WebRtcSignal, WebRtcSignalEnvelope>(), WebRtcSignalProtection {
    override suspend fun doProtect(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope {
        require(context.securityScheme == SignalSecurityScheme.SIGNED) {
            "Context security scheme must be SIGNED for SignedWebRtcSignalProtection but got ${context.securityScheme}"
        }
        val unsigned = WebRtcSignalEnvelope(
            sessionId = input.sessionId,
            kind = input.kind,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.SIGNED),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = null,
            payload = input.payload,
        )
        val signature = signatureProvider.sign(unsigned.encodeForSigning())
        return unsigned.copy(signature = signature)
    }

    override suspend fun doOpen(envelope: WebRtcSignalEnvelope): WebRtcSignal {
        require(envelope.securityScheme == SignalSecurityScheme.SIGNED) {
            "Expected SIGNED security scheme but got ${envelope.securityScheme}"
        }
        val signature = envelope.signature
            ?: throw ProtectionException.AuthenticationFailed(AuthenticationReason.MISSING_SIGNATURE)
        val signatureValid = signatureProvider.verify(
            deviceId = envelope.source,
            message = envelope.encodeForSigning(),
            signature = signature,
        )

        if (!signatureValid) {
            throw ProtectionException.AuthenticationFailed(AuthenticationReason.INVALID_SIGNATURE)
        }

        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Verified signed WebRTC signal envelope",
            fields = mapOf("sessionId" to envelope.sessionId, "source" to envelope.source, "kind" to envelope.kind.name),
        )
        return WebRtcSignal(
            sessionId = envelope.sessionId,
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = envelope.payload,
        )
    }

    override fun observableHeaderValues(envelope: WebRtcSignalEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.webRtcSignalEnvelope.fields

    override fun envelopeLabel(): String = "WebRTC signal envelope"
}

class SignedAndEncryptedWebRtcSignalProtection(
    private val signatureProvider: SignatureProvider,
    private val cryptoSessionManager: CryptoSessionManager,
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<WebRtcSignal, WebRtcSignalEnvelope>(), WebRtcSignalProtection {
    override suspend fun doProtect(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope {
        require(context.securityScheme == SignalSecurityScheme.ENCRYPTED_AND_SIGNED) {
            "Context security scheme must be ENCRYPTED_AND_SIGNED for SignedMessageProtection but got ${context.securityScheme}"
        }

        val encryptedInput = cryptoSessionManager.encryptMessage(
            remoteDeviceId = context.targetDeviceId,
            bytes = input.payload,
        )
        val wirePayload = encryptedInput.encode()

        val unsigned = WebRtcSignalEnvelope(
            sessionId = input.sessionId,
            kind = input.kind,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.SIGNED),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = null,
            payload = wirePayload,
        )

        val signature = signatureProvider.sign(unsigned.encodeForSigning())
        return unsigned.copy(signature = signature)
    }

    override suspend fun doOpen(envelope: WebRtcSignalEnvelope): WebRtcSignal {
        require(envelope.securityScheme == SignalSecurityScheme.ENCRYPTED_AND_SIGNED) {
            "Expected ENCRYPTED_AND_SIGNED security scheme but got ${envelope.securityScheme}"
        }
        val signature = envelope.signature
            ?: throw ProtectionException.AuthenticationFailed(AuthenticationReason.MISSING_SIGNATURE)
        val signatureValid = signatureProvider.verify(
            deviceId = envelope.source,
            message = envelope.encodeForSigning(),
            signature = signature,
        )

        if (!signatureValid) {
            throw ProtectionException.AuthenticationFailed(AuthenticationReason.INVALID_SIGNATURE)
        }

        CryptoWireLimits.requireSessionWireFrameSize(envelope.payload.size)

        val encryptedInput = try {
            SessionWireFrame.decode(envelope.payload)
        } catch (e: Exception) {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode SessionWireFrame from encrypted WebRTC signal envelope",
                throwable = e,
            )
            throw ProtectionException.InvalidEnvelope(e)
        }

        val decryptedInput = try {
            cryptoSessionManager.decryptMessage(
                remoteDeviceId = envelope.source,
                frame = encryptedInput,
            )
        } catch (e: Exception) {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.DECRYPTION_FAILED,
                message = "Failed to decrypt WebRTC signal",
                throwable = e,
            )
            throw ProtectionException.mapDecryptFailure(e)
        }

        val signalPayload = WebRtcSignal(
            sessionId = envelope.sessionId,
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = decryptedInput,
        )

        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Verified signed and encrypted Signal envelope",
            fields = mapOf("sessionId" to envelope.sessionId, "source" to envelope.source, "kind" to envelope.kind.name),
        )
        return signalPayload
    }

    override fun observableHeaderValues(envelope: WebRtcSignalEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.webRtcSignalEnvelope.fields

    override fun envelopeLabel(): String = "WebRTC signal envelope"
}
