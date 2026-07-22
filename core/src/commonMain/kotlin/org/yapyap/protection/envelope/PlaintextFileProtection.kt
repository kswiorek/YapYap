package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.EnvelopeObservability
import org.yapyap.protocol.FieldSensitivity
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.*

// TODO Sprint 5: Replace with real encrypted file protection (SignedAndEncryptedFileProtection).
//  This is a plaintext passthrough placeholder so the orchestrator can compile and boot.
//  Sprint 5 tasks: FileEnvelope lifecycle, chunk scheduler, E2EE for file payloads.
class PlaintextFileProtection(
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : FileProtection {

    override suspend fun protect(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope {
        require(context.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Context security scheme must be PLAINTEXT_TEST_ONLY for PlaintextFileProtection but got ${context.securityScheme}"
        }
        val envelope = FileEnvelope(
            transferId = generateTransferId(input), //TODO transferid
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.PLAINTEXT_TEST_ONLY),
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.encode(),
        )
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Protected file envelope",
            fields = mapOf(
                "transferId" to envelope.transferId,
                "kind" to input.kind.name,
                "source" to envelope.source,
                "target" to envelope.target,
            ),
        )
        return envelope
    }

    override suspend fun open(input: FileEnvelope): OpenedFileEnvelope {
        require(input.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) {
            "Expected PLAINTEXT_TEST_ONLY security scheme but got ${input.securityScheme}"
        }
        val payload = try {
            input.decodePayload()
        } catch (e: Exception) {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode plaintext file envelope",
                throwable = e,
            )
            throw org.yapyap.protection.ProtectionException.InvalidEnvelope(e)
        }
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Opened plaintext file envelope",
            fields = mapOf(
                "transferId" to input.transferId,
                "kind" to payload.kind.name,
                "source" to input.source,
            ),
        )
        return OpenedFileEnvelope(
            transferId = input.transferId,
            source = input.source.id,
            target = input.target.id,
            createdAtEpochSeconds = input.createdAtEpochSeconds,
            securityScheme = input.securityScheme,
            payload = payload,
        )
    }

    override suspend fun decryptChunk(chunk: FilePayload.EncryptedChunk): FileChunk {
        // TODO Sprint 5: Decrypt chunk payload using per-chunk keys or sender key.
        return FileChunk(
            fileName = "",
            chunkIndex = chunk.chunkIndex,
            chunkCount = chunk.chunkCount,
            type = FileType.GENERIC,
            fileData = chunk.chunkCiphertext,
        )
    }

    fun observableHeaderValues(envelope: FileEnvelope): Map<String, Any?> =
        envelope.observableHeaderValues()

    fun observabilityPolicy(): Map<String, FieldSensitivity> =
        EnvelopeObservability.fileEnvelope.fields

    private suspend fun generateTransferId(input: FilePayload): String {
        val kindPrefix = input.kind.name.lowercase()
        val hash = cryptoProvider.sha256(input.encode()).take(8)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "$kindPrefix-$hash"
    }
}
