package org.yapyap.backend.protection

import org.yapyap.backend.crypto.CryptoProvider
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
    suspend fun open(envelope: SystemEnvelope): SystemPayload
    suspend fun protect(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope
}

class PlaintextSystemProtection(
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<SystemPayload, SystemEnvelope>(), SystemProtection {
    override suspend fun doProtect(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope {
        require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Context security scheme must be PLAINTEXT_TEST_ONLY for PlaintextSystemProtection but got ${context.securityScheme}"
        }
        return SystemEnvelope(
            correlationId = systemEnvelopeCorrelationId(input),
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.PLAINTEXT_TEST_ONLY),
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.encode(),
        )
    }

    override suspend fun doOpen(envelope: SystemEnvelope): SystemPayload {
        require(envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Expected PLAINTEXT_TEST_ONLY security scheme but got ${envelope.securityScheme}"
        }
        val systemPayload = SystemPayload.decode(envelope.payload)
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Opened plaintext system envelope",
            fields = mapOf("correlationId" to envelope.correlationId, "kind" to systemPayload.kind.name),
        )
        return systemPayload
    }

    override fun observableHeaderValues(envelope: SystemEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.systemEnvelope.fields

    override fun envelopeLabel(): String = "System envelope"
}

class SignedSystemProtection(
    private val signatureProvider: SignatureProvider,
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : BaseProtection<SystemPayload, SystemEnvelope>(), SystemProtection {
    override suspend fun doProtect(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope {
        require(context.securityScheme == SignalSecurityScheme.SIGNED) {
            "Context security scheme must be SIGNED for SignedSystemProtection but got ${context.securityScheme}"
        }
        val unsigned = SystemEnvelope(
            correlationId = systemEnvelopeCorrelationId(input),
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

    override suspend fun doOpen(envelope: SystemEnvelope): SystemPayload {
        require(envelope.securityScheme == SignalSecurityScheme.SIGNED) {
            "Expected SIGNED security scheme but got ${envelope.securityScheme}"
        }
        val signature = envelope.signature ?: error("SIGNED system envelope must contain signature")
        require(
            signatureProvider.verify(
                deviceId = envelope.source,
                message = envelope.encodeForSigning(),
                signature = signature,
            ),
        ) {
            "System envelope signature verification failed for source=${envelope.source}"
        }
        val systemPayload = SystemPayload.decode(envelope.payload)
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Verified signed system envelope",
            fields = mapOf(
                "correlationId" to envelope.correlationId,
                "source" to envelope.source,
                "kind" to systemPayload.kind.name,
            ),
        )
        return systemPayload
    }

    override fun observableHeaderValues(envelope: SystemEnvelope): Map<String, Any?> = envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.systemEnvelope.fields

    override fun envelopeLabel(): String = "System envelope"
}

private fun systemEnvelopeCorrelationId(payload: SystemPayload): String =
    when (payload) {
        is SystemPayload.PacketAck -> "ack:${payload.packetId.toHex()}"
        is SystemPayload.PacketNack -> "nack:${payload.packetId.toHex()}"
    }
