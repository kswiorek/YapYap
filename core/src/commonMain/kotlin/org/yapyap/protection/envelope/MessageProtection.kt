package org.yapyap.protection.envelope

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.manager.CryptoSessionManager
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.AuthenticationReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.EnvelopeObservability
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload

interface MessageProtection {
    suspend fun open(envelope: MessageEnvelope): MessagePayload
    suspend fun protect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope
}

class PlaintextMessageProtection(
    private val cryptoProvider: CryptoProvider,
) : BaseProtection<MessagePayload, MessageEnvelope>(), MessageProtection {
    override suspend fun doProtect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Context security scheme must be PLAINTEXT_TEST_ONLY for PlaintextMessageProtection but got ${context.securityScheme}"
        }
        return MessageEnvelope(
            messageEnvelopeId = input.messageId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
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
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode plaintext message envelope",
                throwable = e,
            )
            throw ProtectionException.InvalidEnvelope(e)
        }
        AppLog.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Opened plaintext message envelope",
            fields = mapOf("messageId" to envelope.messageEnvelopeId, "payloadType" to messagePayload.payloadType.name),
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
) : BaseProtection<MessagePayload, MessageEnvelope>(), MessageProtection {
    override suspend fun doProtect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        require(context.securityScheme == SignalSecurityScheme.SIGNED) {
            "Context security scheme must be SIGNED for SignedMessageProtection but got ${context.securityScheme}"
        }
        val unsigned = MessageEnvelope(
            messageEnvelopeId = input.messageId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
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

        val messagePayload = try {
            envelope.decodePayload()
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode signed message envelope",
                throwable = e,
            )
            throw ProtectionException.InvalidEnvelope(e)
        }

        AppLog.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Verified signed message envelope",
            fields = mapOf("messageId" to envelope.messageEnvelopeId, "source" to envelope.source, "payloadType" to messagePayload.payloadType.name),
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
) : BaseProtection<MessagePayload, MessageEnvelope>(), MessageProtection {
    override suspend fun doProtect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        require(context.securityScheme == SignalSecurityScheme.ENCRYPTED_AND_SIGNED) {
            "Context security scheme must be SIGNED for SignedMessageProtection but got ${context.securityScheme}"
        }

        val wirePayload = try {
            cryptoSessionManager.encryptMessage(
                remoteDeviceId = context.targetDeviceId,
                bytes = input.encode(),
            )
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENCRYPTION_FAILED,
                message = "Failed to encrypt message",
                throwable = e,
            )
            throw ProtectionException.mapEncryptDecryptFailure(e)
        }

        val unsigned = MessageEnvelope(
            messageEnvelopeId = input.messageId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.ENCRYPTED_AND_SIGNED),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = null,
            payload = wirePayload,
        )

        val signature = try {
            signatureProvider.sign(unsigned.encodeForSigning())
        } catch (e: CryptoException) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.SIGNATURE_SIGN_FAILED,
                message = "Failed to sign message envelope",
                throwable = e,
            )
            throw ProtectionException.IdentityNotReady(e)
        }
        return unsigned.copy(signature = signature)
    }

    override suspend fun doOpen(envelope: MessageEnvelope): MessagePayload {
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

        val decryptedInput = try {
            cryptoSessionManager.decryptMessage(
                remoteDeviceId = envelope.source,
                frameBytes = envelope.payload,
            )
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.DECRYPTION_FAILED,
                message = "Failed to decrypt message",
                throwable = e,
            )
            throw ProtectionException.mapEncryptDecryptFailure(e)
        }

        val messagePayload = try {
            MessagePayload.decode(decryptedInput)
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode MessagePayload from decrypted message",
                throwable = e,
            )
            throw ProtectionException.InvalidEnvelope(e)
        }

        AppLog.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Verified signed and encrypted message envelope",
            fields = mapOf("messageId" to envelope.messageEnvelopeId, "source" to envelope.source, "payloadType" to messagePayload.payloadType.name),
        )
        return messagePayload
    }

    override fun observableHeaderValues(envelope: MessageEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.messageEnvelope.fields

    override fun envelopeLabel(): String = "Message envelope"
}
