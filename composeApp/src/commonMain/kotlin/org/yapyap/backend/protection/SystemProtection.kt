package org.yapyap.backend.protection

import org.yapyap.backend.crypto.SignatureProvider
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.SystemEnvelope
import org.yapyap.backend.protocol.SystemPayload
import org.yapyap.backend.routing.EnvelopeObservability

interface SystemProtection {
    fun open(envelope: SystemEnvelope): SystemPayload
    fun protect(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope
}

class PlaintextSystemProtection(
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<SystemPayload, SystemEnvelope>(), SystemProtection {
    override fun doProtect(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope {
        require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Context security scheme must be PLAINTEXT_TEST_ONLY for PlaintextSystemProtection but got ${context.securityScheme}"
        }
        return SystemEnvelope(
            correlationId = systemEnvelopeCorrelationId(input),
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = context.nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input,
        )
    }

    override fun doOpen(envelope: SystemEnvelope): SystemPayload {
        require(envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Expected PLAINTEXT_TEST_ONLY security scheme but got ${envelope.securityScheme}"
        }
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Opened plaintext system envelope",
            fields = mapOf("correlationId" to envelope.correlationId, "kind" to envelope.kind.name),
        )
        return envelope.decodePayload()
    }

    override fun observableHeaderValues(envelope: SystemEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.systemEnvelope.fields

    override fun envelopeLabel(): String = "System envelope"
}

class SignedSystemProtection(
    private val signatureProvider: SignatureProvider,
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<SystemPayload, SystemEnvelope>(), SystemProtection {
    override fun doProtect(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope {
        require(context.securityScheme == SignalSecurityScheme.SIGNED) {
            "Context security scheme must be SIGNED for SignedSystemProtection but got ${context.securityScheme}"
        }
        val encodedPayload = input.encode()
        val correlationId = systemEnvelopeCorrelationId(input)
        val signingPayload = buildSigningPayload(
            envelopeId = correlationId,
            kindWireValue = input.kind.wireValue,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = context.nonce,
            protectedPayload = encodedPayload,
        )
        val signature = signatureProvider.signDetached(signingPayload)
        return SystemEnvelope(
            correlationId = correlationId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = context.nonce,
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = signature,
            payload = input,
        )
    }

    override fun doOpen(envelope: SystemEnvelope): SystemPayload {
        require(envelope.securityScheme == SignalSecurityScheme.SIGNED) {
            "Expected SIGNED security scheme but got ${envelope.securityScheme}"
        }
        val signature = envelope.signature ?: error("SIGNED system envelope must contain signature")
        val protectedPayload = envelope.payload.encode()
        val signingPayload = buildSigningPayload(
            envelopeId = envelope.correlationId,
            kindWireValue = envelope.kind.wireValue,
            source = envelope.source,
            target = envelope.target,
            createdAtEpochSeconds = envelope.createdAtEpochSeconds,
            nonce = envelope.nonce,
            protectedPayload = protectedPayload,
        )
        require(signatureProvider.verifyDetached(envelope.source, signingPayload, signature)) {
            "System envelope signature verification failed for source=${envelope.source}"
        }
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Verified signed system envelope",
            fields = mapOf(
                "correlationId" to envelope.correlationId,
                "source" to envelope.source,
                "kind" to envelope.kind.name,
            ),
        )
        return envelope.decodePayload()
    }

    override fun observableHeaderValues(envelope: SystemEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.systemEnvelope.fields

    override fun envelopeLabel(): String = "System envelope"
}

private fun systemEnvelopeCorrelationId(payload: SystemPayload): String =
    when (payload) {
        is SystemPayload.PacketAck -> "ack:${payload.acknowledgedPacketId.toHex()}"
        is SystemPayload.PacketNack -> "nack:${payload.rejectedPacketId.toHex()}"
    }
