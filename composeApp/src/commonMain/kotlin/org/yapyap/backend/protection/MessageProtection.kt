package org.yapyap.backend.protection

import org.yapyap.backend.crypto.SignatureProvider
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.routing.EnvelopeObservability

interface MessageProtection {
    fun open(envelope: MessageEnvelope): MessagePayload
    fun protect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope
}

class PlaintextMessageProtection(
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<MessagePayload, MessageEnvelope>(), MessageProtection {
    override fun doProtect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Context security scheme must be PLAINTEXT_TEST_ONLY for PlaintextMessageProtection but got ${context.securityScheme}"
        }
        return MessageEnvelope(
            messageId = messageEnvelopeId(input),
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
        require(envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Expected PLAINTEXT_TEST_ONLY security scheme but got ${envelope.securityScheme}"
        }
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.SIGNAL_INBOUND_HANDLED,
            message = "Opened plaintext message envelope",
            fields = mapOf("messageId" to envelope.messageId, "kind" to envelope.kind.name),
        )
        return envelope.decodePayload()
    }

    override fun observableHeaderValues(envelope: MessageEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.messageEnvelope.fields

    override fun envelopeLabel(): String = "Message envelope"
}

class SignedMessageProtection(
    private val signatureProvider: SignatureProvider,
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<MessagePayload, MessageEnvelope>(), MessageProtection {
    override fun doProtect(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        require(context.securityScheme == SignalSecurityScheme.SIGNED) {
            "Context security scheme must be SIGNED for SignedMessageProtection but got ${context.securityScheme}"
        }
        val encodedPayload = input.encode()
        val envelopeId = messageEnvelopeId(input)
        val signingPayload = buildSigningPayload(
            envelopeId = envelopeId,
            kindWireValue = input.kind.wireValue,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = context.nonce,
            protectedPayload = encodedPayload,
        )
        val signature = signatureProvider.signDetached(signingPayload)
        return MessageEnvelope(
            messageId = envelopeId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = context.nonce,
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = signature,
            payload = input,
        )
    }

    override fun doOpen(envelope: MessageEnvelope): MessagePayload {
        require(envelope.securityScheme == SignalSecurityScheme.SIGNED) {
            "Expected SIGNED security scheme but got ${envelope.securityScheme}"
        }
        val signature = envelope.signature ?: error("SIGNED message envelope must contain signature")
        val protectedPayload = envelope.payload.encode()
        val signingPayload = buildSigningPayload(
            envelopeId = envelope.messageId,
            kindWireValue = envelope.kind.wireValue,
            source = envelope.source,
            target = envelope.target,
            createdAtEpochSeconds = envelope.createdAtEpochSeconds,
            nonce = envelope.nonce,
            protectedPayload = protectedPayload,
        )
        require(signatureProvider.verifyDetached(envelope.source, signingPayload, signature)) {
            "Message signature verification failed for source=${envelope.source}"
        }
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.SIGNAL_INBOUND_HANDLED,
            message = "Verified signed message envelope",
            fields = mapOf("messageId" to envelope.messageId, "source" to envelope.source, "kind" to envelope.kind.name),
        )
        return envelope.decodePayload()
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