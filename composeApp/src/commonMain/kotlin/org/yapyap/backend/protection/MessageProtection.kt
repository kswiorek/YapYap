package org.yapyap.backend.protection

import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.SignatureProvider
import org.yapyap.backend.crypto.e2ee.CryptoSessionManager
import org.yapyap.backend.crypto.e2ee.CryptoWireLimits
import org.yapyap.backend.crypto.e2ee.SessionWireFrame
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.routing.EnvelopeObservability

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
            messageId = messageEnvelopeId(input),
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
        val messagePayload = envelope.decodePayload()
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
            messageId = messageEnvelopeId(input),
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
        val signature = envelope.signature ?: error("SIGNED message envelope must contain signature")
        require(
            signatureProvider.verify(
                deviceId = envelope.source,
                message = envelope.encodeForSigning(),
                signature = signature,
            ),
        ) {
            "Message signature verification failed for source=${envelope.source}"
        }
        val messagePayload = envelope.decodePayload()

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
            messageId = messageEnvelopeId(input),
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
        val signature = envelope.signature ?: error("ENCRYPTED_AND_SIGNED message envelope must contain signature")
        require(
            signatureProvider.verify(
                deviceId = envelope.source,
                message = envelope.encodeForSigning(),
                signature = signature,
            ),
        ) {
            "Message signature verification failed for source=${envelope.source}"
        }

        CryptoWireLimits.requireSessionWireFrameSize(envelope.payload.size)
        val encryptedInput = SessionWireFrame.decode(envelope.payload)
        val decryptedInput = cryptoSessionManager.decryptMessage(
            remoteDeviceId = envelope.source,
            frame = encryptedInput,
        )
        val messagePayload = MessagePayload.decode(decryptedInput)

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

private fun messageEnvelopeId(payload: MessagePayload): String =
    when (payload) {
        is MessagePayload.Text -> payload.messageId
        is MessagePayload.GlobalEvent -> payload.messageId
    }
