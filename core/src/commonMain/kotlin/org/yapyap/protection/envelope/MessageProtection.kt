package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.e2ee.CryptoSessionManager
import org.yapyap.crypto.e2ee.CryptoWireLimits
import org.yapyap.crypto.e2ee.SessionWireFrame
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.EnvelopeObservability

interface MessageProtection {
    suspend fun open(envelope: MessageEnvelope): MessagePayload
    suspend fun protect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope
}

class PlaintextMessageProtection(
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<MessagePayload, MessageEnvelope>(), MessageProtection {
    override suspend fun doProtect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Context security scheme must be PLAINTEXT_TEST_ONLY for PlaintextMessageProtection but got ${context.securityScheme}"
        }
        return MessageEnvelope(
            messageId = input.messageId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.PLAINTEXT_TEST_ONLY),
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.encode(),
        )
    }

    override suspend fun doOpen(envelope: MessageEnvelope): MessagePayload {
        require(envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Expected PLAINTEXT_TEST_ONLY security scheme but got ${envelope.securityScheme}"
        }
        val messagePayload = try {
            envelope.decodePayload()
        } catch (e: Exception) {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode plaintext message envelope",
                throwable = e,
            )
            throw ProtectionException.DecodeError()
        }
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Opened plaintext message envelope",
            fields = mapOf("messageId" to envelope.messageId, "kind" to messagePayload.kind.name),
        )
        return messagePayload
    }

    override fun observableHeaderValues(envelope: MessageEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.messageEnvelope.fields

    override fun envelopeLabel(): String = "Message envelope"
}

class SignedMessageProtection(
    private val signatureProvider: SignatureProvider,
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<MessagePayload, MessageEnvelope>(), MessageProtection {
    override suspend fun doProtect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        require(context.securityScheme == SignalSecurityScheme.SIGNED) {
            "Context security scheme must be SIGNED for SignedMessageProtection but got ${context.securityScheme}"
        }
        val unsigned = MessageEnvelope(
            messageId = input.messageId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.SIGNED),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = null,
            payload = input.encode(),
        )
        val signature = signatureProvider.sign(unsigned.encodeForSigning())
        return unsigned.copy(signature = signature)
    }

    override suspend fun doOpen(envelope: MessageEnvelope): MessagePayload {
        require(envelope.securityScheme == SignalSecurityScheme.SIGNED) {
            "Expected SIGNED security scheme but got ${envelope.securityScheme}"
        }
        val signature = envelope.signature ?: throw ProtectionException.SignatureMissing()
        val signatureValid = signatureProvider.verify(
            deviceId = envelope.source,
            message = envelope.encodeForSigning(),
            signature = signature,
        )

        if (!signatureValid){
            throw ProtectionException.SignatureVerificationFailed()
        }

        val messagePayload = try {
            envelope.decodePayload()
        } catch (e: Exception) {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode signed message envelope",
                throwable = e,
            )
            throw ProtectionException.DecodeError()
        }

        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Verified signed message envelope",
            fields = mapOf("messageId" to envelope.messageId, "source" to envelope.source, "kind" to messagePayload.kind.name),
        )
        return messagePayload
    }

    override fun observableHeaderValues(envelope: MessageEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.messageEnvelope.fields

    override fun envelopeLabel(): String = "Message envelope"
}

class SignedAndEncryptedMessageProtection(
    private val signatureProvider: SignatureProvider,
    private val cryptoSessionManager: CryptoSessionManager,
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<MessagePayload, MessageEnvelope>(), MessageProtection {
    override suspend fun doProtect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        require(context.securityScheme == SignalSecurityScheme.ENCRYPTED_AND_SIGNED) {
            "Context security scheme must be SIGNED for SignedMessageProtection but got ${context.securityScheme}"
        }

        val encryptedInput = cryptoSessionManager.encryptMessage(
            remoteDeviceId = context.targetDeviceId,
            bytes = input.encode(),
        )
        val wirePayload = encryptedInput.encode()

        val unsigned = MessageEnvelope(
            messageId = input.messageId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.ENCRYPTED_AND_SIGNED),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = null,
            payload = wirePayload,
        )

        val signature = signatureProvider.sign(unsigned.encodeForSigning())
        return unsigned.copy(signature = signature)
    }

    override suspend fun doOpen(envelope: MessageEnvelope): MessagePayload {
        require(envelope.securityScheme == SignalSecurityScheme.ENCRYPTED_AND_SIGNED) {
            "Expected ENCRYPTED_AND_SIGNED security scheme but got ${envelope.securityScheme}"
        }
        val signature = envelope.signature ?: throw ProtectionException.SignatureMissing()
        val signatureValid = signatureProvider.verify(
            deviceId = envelope.source,
            message = envelope.encodeForSigning(),
            signature = signature,
        )

        if (!signatureValid){
            throw ProtectionException.SignatureVerificationFailed()
        }

        CryptoWireLimits.requireSessionWireFrameSize(envelope.payload.size)

        val encryptedInput = try {
            SessionWireFrame.decode(envelope.payload)
        } catch (e: Exception) {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode SessionWireFrame from encrypted message envelope",
                throwable = e,
            )
            throw ProtectionException.DecodeError()
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
                message = "Failed to decrypt message",
                throwable = e,
            )
            throw e
        }

        val messagePayload = try {
            MessagePayload.decode(decryptedInput)
        } catch (e: Exception) {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode MessagePayload from decrypted message",
                throwable = e,
            )
            throw ProtectionException.DecodeError()
        }

        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Verified signed and encrypted message envelope",
            fields = mapOf("messageId" to envelope.messageId, "source" to envelope.source, "kind" to messagePayload.kind.name),
        )
        return messagePayload
    }

    override fun observableHeaderValues(envelope: MessageEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.messageEnvelope.fields

    override fun envelopeLabel(): String = "Message envelope"
}
