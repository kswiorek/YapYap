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
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.EnvelopeObservability
import org.yapyap.protocol.FieldSensitivity
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FileEnvelopeKind
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Real small-path file protection (design §4), replacing `PlaintextFileProtection`.
 *
 * - OFFER sealed halves and REQUEST bodies go through the standard Double Ratchet
 *   session machinery (one small frame per transfer), like messages.
 * - Chunk content never touches the ratchet: per-transfer symmetric AEAD
 *   (ChaCha20-Poly1305, library-managed IV) with AAD = (`transferId`, `chunkIndex`),
 *   so a relay can neither reorder chunks nor splice them across transfers.
 * - Every FILE envelope is signed by its author; relays replay the original signature.
 * - Failures map to [ProtectionDisposition] (`PERMANENT` / `DEFER`) so crypto
 *   exceptions never cross into routing (guide §3).
 */
internal class SignedAndEncryptedFileProtection(
    private val signatureProvider: SignatureProvider,
    private val cryptoSessionManager: CryptoSessionManager,
    private val cryptoProvider: CryptoProvider,
) : FileProtection {

    override suspend fun protect(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope {
        val envelope = try {
            doProtect(input, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.map(e)
        }
        assertObservabilityContract(
            observableHeaderValues = envelope.observableHeaderValues(),
            policy = EnvelopeObservability.fileEnvelope.fields,
        )
        return envelope
    }

    override suspend fun open(input: FileEnvelope): OpenedFileEnvelope {
        assertObservabilityContract(
            observableHeaderValues = input.observableHeaderValues(),
            policy = EnvelopeObservability.fileEnvelope.fields,
        )
        return try {
            doOpen(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.map(e)
        }
    }

    private suspend fun doProtect(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope {
        require(context.securityScheme == SignalSecurityScheme.ENCRYPTED_AND_SIGNED) {
            "Context security scheme must be ENCRYPTED_AND_SIGNED for SignedAndEncryptedFileProtection but got ${context.securityScheme}"
        }
        val transferId = requireNotNull(context.transferId) {
            "File protection requires transferId in context"
        }
        val payloadBytes = when (input) {
            // The sealed half was sealed explicitly by the pusher (it owns the key);
            // the REQUEST body is sealed here, like a message (session only, no key).
            is FilePayload.Offer -> input.encode()
            is FilePayload.Request -> sealRequestWire(input, context.targetDeviceId)
            else -> input.encode()
        }
        val unsigned = FileEnvelope(
            transferId = transferId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.ENCRYPTED_AND_SIGNED),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = null,
            payload = payloadBytes,
        )
        val signature = try {
            signatureProvider.sign(unsigned.encodeForSigning())
        } catch (e: CryptoException) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.SIGNATURE_SIGN_FAILED,
                message = "Failed to sign file envelope",
                throwable = e,
            )
            throw ProtectionException.IdentityNotReady(e)
        }
        return unsigned.copy(signature = signature)
    }

    private suspend fun doOpen(envelope: FileEnvelope): OpenedFileEnvelope {
        val signature = envelope.signature
            ?: throw ProtectionException.AuthenticationFailed(AuthenticationReason.MISSING_SIGNATURE)
        require(envelope.securityScheme == SignalSecurityScheme.ENCRYPTED_AND_SIGNED) {
            "Expected ENCRYPTED_AND_SIGNED security scheme but got ${envelope.securityScheme}"
        }
        val valid = signatureProvider.verify(
            deviceId = envelope.source,
            message = envelope.encodeForSigning(),
            signature = signature,
        )
        if (!valid) {
            throw ProtectionException.AuthenticationFailed(AuthenticationReason.INVALID_SIGNATURE)
        }
        val kind = try {
            require(envelope.payload.isNotEmpty()) { "Empty file payload" }
            FileEnvelopeKind.fromWireValue(envelope.payload[0])
        } catch (e: Exception) {
            throw ProtectionException.InvalidEnvelope(e)
        }
        val payload = try {
            when (kind) {
                FileEnvelopeKind.OFFER -> FilePayload.Offer.decode(envelope.payload)
                FileEnvelopeKind.CHUNK -> FilePayload.EncryptedChunk.decode(envelope.payload)
                FileEnvelopeKind.ACK -> FilePayload.Ack.decode(envelope.payload)
                FileEnvelopeKind.COMPLETE -> FilePayload.Complete.decode(envelope.payload)
                FileEnvelopeKind.REQUEST -> openRequestWire(envelope.payload, envelope.source)
                FileEnvelopeKind.CANCEL -> FilePayload.Cancel.decode(envelope.payload)
            }
        } catch (e: ProtectionException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode file envelope payload",
                throwable = e,
            )
            throw ProtectionException.InvalidEnvelope(e)
        }
        AppLog.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Verified signed file envelope",
            fields = mapOf(
                "transferId" to envelope.transferId,
                "kind" to payload.kind.name,
                "source" to envelope.source,
            ),
        )
        return OpenedFileEnvelope(
            transferId = envelope.transferId,
            source = envelope.source.id,
            target = envelope.target.id,
            createdAt = envelope.createdAt,
            securityScheme = envelope.securityScheme,
            payload = payload,
        )
    }

    override suspend fun encryptChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(key.size == TRANSFER_KEY_SIZE_BYTES) {
            "Transfer key must be $TRANSFER_KEY_SIZE_BYTES bytes but was ${key.size}"
        }
        require(chunkIndex >= 0) { "chunkIndex must be >= 0 but was $chunkIndex" }
        return try {
            cryptoProvider.encryptAead(key, plaintext, chunkAad(transferId, chunkIndex))
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENCRYPTION_FAILED,
                message = "Failed to encrypt file chunk",
                throwable = e,
            )
            throw ProtectionException.mapEncryptFailure(e)
        }
    }

    override suspend fun decryptChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        chunk: FilePayload.EncryptedChunk,
    ): ByteArray {
        require(key.size == TRANSFER_KEY_SIZE_BYTES) {
            "Transfer key must be $TRANSFER_KEY_SIZE_BYTES bytes but was ${key.size}"
        }
        if (chunk.chunkIndex != chunkIndex) {
            throw ProtectionException.AuthenticationFailed(
                AuthenticationReason.DECRYPT_AUTH_FAILED,
                IllegalArgumentException(
                    "Chunk index ${chunk.chunkIndex} does not match expected $chunkIndex",
                ),
            )
        }
        return try {
            cryptoProvider.decryptAead(key, chunk.chunkCiphertext, chunkAad(transferId, chunkIndex))
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.DECRYPTION_FAILED,
                message = "Failed to decrypt file chunk",
                throwable = e,
            )
            throw ProtectionException.mapEncryptDecryptFailure(e)
        }
    }

    override suspend fun sealOffer(offer: FilePayload.SealedOffer, target: PeerId): ByteArray {
        return try {
            cryptoSessionManager.encryptMessage(
                remoteDeviceId = target,
                bytes = offer.encode(),
            )
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENCRYPTION_FAILED,
                message = "Failed to seal file OFFER half",
                throwable = e,
            )
            throw ProtectionException.mapEncryptFailure(e)
        }
    }

    override suspend fun openOffer(sealed: ByteArray, source: PeerId): FilePayload.SealedOffer {
        val plaintext = try {
            cryptoSessionManager.decryptMessage(
                remoteDeviceId = source,
                frameBytes = sealed,
            )
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.DECRYPTION_FAILED,
                message = "Failed to open file OFFER half",
                throwable = e,
            )
            throw ProtectionException.mapEncryptDecryptFailure(e)
        }
        return try {
            FilePayload.SealedOffer.decode(plaintext)
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode sealed OFFER half",
                throwable = e,
            )
            throw ProtectionException.InvalidEnvelope(e)
        }
    }

    override fun generateTransferKey(): ByteArray = generateTransferKey(cryptoProvider)

    /**
     * Sealed REQUEST wire form: kind byte in clear (dispatch + direct-only link sees
     * a REQUEST) with the fields ratchet-sealed after it. The `fileId` and coverage
     * never travel in clear.
     */
    private suspend fun sealRequestWire(request: FilePayload.Request, target: PeerId): ByteArray {
        val encoded = request.encode()
        val fields = encoded.copyOfRange(1, encoded.size)
        val sealed = try {
            cryptoSessionManager.encryptMessage(
                remoteDeviceId = target,
                bytes = fields,
            )
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENCRYPTION_FAILED,
                message = "Failed to seal file REQUEST",
                throwable = e,
            )
            throw ProtectionException.mapEncryptFailure(e)
        }
        return byteArrayOf(FileEnvelopeKind.REQUEST.wireValue) + sealed
    }

    private suspend fun openRequestWire(payload: ByteArray, source: PeerId): FilePayload.Request {
        val sealed = payload.copyOfRange(1, payload.size)
        val fields = try {
            cryptoSessionManager.decryptMessage(
                remoteDeviceId = source,
                frameBytes = sealed,
            )
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.DECRYPTION_FAILED,
                message = "Failed to open file REQUEST",
                throwable = e,
            )
            throw ProtectionException.mapEncryptDecryptFailure(e)
        }
        return try {
            FilePayload.Request.decode(byteArrayOf(FileEnvelopeKind.REQUEST.wireValue) + fields)
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode sealed REQUEST",
                throwable = e,
            )
            throw ProtectionException.InvalidEnvelope(e)
        }
    }

    private fun assertObservabilityContract(
        observableHeaderValues: Map<String, Any?>,
        policy: Map<String, FieldSensitivity>,
    ) {
        val unexpectedProtectedCleartext = observableHeaderValues.keys
            .filter { policy[it] == FieldSensitivity.PROTECTED }
        require(unexpectedProtectedCleartext.isEmpty()) {
            "File envelope exposes protected fields in cleartext: $unexpectedProtectedCleartext"
        }
    }

    companion object {
        const val TRANSFER_KEY_SIZE_BYTES: Int = 32

        /**
         * Chunk AEAD associated data: the transfer id (16 bytes) plus the chunk index
         * (u32 BE). Pins every chunk to its transfer and position — a relay can neither
         * reorder chunks nor splice them across transfers. Nothing relies on ciphertext
         * being reproducible.
         */
        internal fun chunkAad(transferId: TransferId, chunkIndex: Int): ByteArray {
            val writer = ByteWriter(20)
            writer.writeUuid(transferId.value)
            writer.writeInt(chunkIndex)
            return writer.toByteArray()
        }

        /** Fresh per-transfer symmetric key (design §4): one key never outlives one transfer. */
        fun generateTransferKey(crypto: CryptoProvider): ByteArray =
            crypto.randomBytes(TRANSFER_KEY_SIZE_BYTES)
    }
}
