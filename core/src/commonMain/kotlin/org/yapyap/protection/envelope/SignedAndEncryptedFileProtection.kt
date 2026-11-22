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
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.EnvelopeObservability
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TRANSFER_KEY_BYTES
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.TransferKey
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FileEnvelopeKind
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import kotlin.coroutines.cancellation.CancellationException

/**
 * Small-path file protection (design §4):
 * - OFFER sealed halves and REQUEST bodies go through the standard Double Ratchet
 *   session machinery (one small frame per transfer), like messages.
 * - Chunk content never touches the ratchet: per-transfer symmetric AEAD
 *   (ChaCha20-Poly1305, library-managed IV) with AAD = (`transferId`, `chunkIndex`),
 *   so a relay can neither reorder chunks nor splice them across transfers.
 * - Every FILE envelope is signed by its author; relays replay the original signature.
 * - Failures map to [ProtectionException] dispositions (`PERMANENT` / `DEFER`), so crypto
 *   exceptions never cross into routing (guide §3).
 *
 * Keys are [TransferKey] threaded explicitly per call: the pusher owns its
 * `file_pushes` row key and passes it to [encryptChunk]; the inbound handler owns its
 * `file_incoming_transfers` row key and passes it to [decryptChunk]. Protection never
 * persists a key (sealed frames it opened stay in memory for a few minutes, see
 * [OpenedFrameCache]), and the orchestrator never sees one.
 */
internal class SignedAndEncryptedFileProtection(
    private val signatureProvider: SignatureProvider,
    private val cryptoSessionManager: CryptoSessionManager,
    private val cryptoProvider: CryptoProvider,
    /** Lets a redelivery of a sealed frame we deferred after opening through (see [OpenedFrameCache]). */
    private val openedFrames: OpenedFrameCache = OpenedFrameCache(),
) : BaseProtection<FilePayload, FileEnvelope, OpenedFileEnvelope, FileProtectContext>(),
    FileEnvelopeProtection {

    private suspend fun openSealed(source: PeerId, sealed: ByteArray): ByteArray =
        openedFrames.openOnce(source, sealed, digest = { cryptoProvider.sha256(it) }) {
            cryptoSessionManager.decryptMessage(remoteDeviceId = source, frameBytes = sealed)
        }

    override suspend fun doProtect(input: FilePayload, context: FileProtectContext): FileEnvelope {
        val payloadBytes = when (input) {
            is FilePayload.Offer -> input.encode()
            is FilePayload.Request -> sealRequestWire(input, context.targetDeviceId)
            else -> input.encode()
        }
        val unsigned = FileEnvelope(
            transferId = context.transferId,
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
        } catch (e: CancellationException) {
            throw e
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

    override suspend fun doOpen(envelope: FileEnvelope): OpenedFileEnvelope {
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
            source = envelope.source,
            target = envelope.target,
            createdAt = envelope.createdAt,
            securityScheme = envelope.securityScheme,
            payload = payload,
        )
    }

    override fun observableHeaderValues(envelope: FileEnvelope): Map<String, Any?> =
        envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.fileEnvelope.fields

    override fun envelopeLabel(): String = "File envelope"

    override fun observablePayloadValuesForProtect(input: FilePayload): Map<String, Any?> =
        input.observablePublicValues()

    override fun observablePayloadValuesForOpen(opened: OpenedFileEnvelope): Map<String, Any?> =
        opened.payload.observablePublicValues()

    override fun payloadObservabilityPolicy() = EnvelopeObservability.filePayload.fields

    override suspend fun protectOffer(
        publicHalf: FileOfferPublicHalf,
        sealed: FileOfferSealedManifest,
        key: TransferKey,
        ctx: FileProtectContext,
    ): FileEnvelope {
        // Both halves' caps are checked here, inside protection: a bad name, block list or
        // chunking is a PERMANENT protection failure, never a raw exception at the caller.
        val sealedOffer = invalidAsProtection {
            FilePayload.SealedOffer(
                fileId = sealed.fileId,
                blockSize = sealed.blockSize,
                blockHashes = sealed.blockHashes,
                transferKey = key,
                fileName = sealed.fileName,
                mimeType = sealed.mimeType,
            )
        }
        val sealedBytes = sealOffer(sealedOffer, ctx.targetDeviceId)
        val offer = invalidAsProtection {
            FilePayload.Offer(
                chunkSizeBytes = publicHalf.chunkSizeBytes,
                totalBytes = publicHalf.totalBytes,
                startIndex = publicHalf.startIndex,
                sealedOfferBytes = sealedBytes,
            )
        }
        return protect(offer, ctx)
    }

    private inline fun <T> invalidAsProtection(build: () -> T): T = try {
        build()
    } catch (e: IllegalArgumentException) {
        throw ProtectionException.InvalidEnvelope(e)
    }

    override suspend fun encryptChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: TransferKey,
        plaintext: ByteArray,
    ): ByteArray {
        if (chunkIndex < 0) {
            throw ProtectionException.InvalidEnvelope(
                IllegalArgumentException("chunkIndex must be >= 0 but was $chunkIndex"),
            )
        }
        return try {
            cryptoProvider.encryptAead(key.bytes, plaintext, chunkAad(transferId, chunkIndex))
        } catch (e: CancellationException) {
            throw e
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
        key: TransferKey,
        chunk: FilePayload.EncryptedChunk,
    ): ByteArray {
        return try {
            cryptoProvider.decryptAead(key.bytes, chunk.chunkCiphertext, chunkAad(transferId, chunk.chunkIndex))
        } catch (e: CancellationException) {
            throw e
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

    /**
     * Ratchet-seals an OFFER's sealed half to [target] (the key's only carrier). Not on the port:
     * routing builds OFFERs with [protectOffer]; tests use this to craft OFFERs by hand.
     */
    suspend fun sealOffer(offer: FilePayload.SealedOffer, target: PeerId): ByteArray {
        return try {
            cryptoSessionManager.encryptMessage(
                remoteDeviceId = target,
                bytes = offer.encode(),
            )
        } catch (e: CancellationException) {
            throw e
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
            openSealed(source, sealed)
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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

    override fun generateTransferKey(): TransferKey = generateTransferKey(cryptoProvider)

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
        } catch (e: CancellationException) {
            throw e
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
            openSealed(source, sealed)
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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

    companion object {
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
        fun generateTransferKey(crypto: CryptoProvider): TransferKey =
            TransferKey(crypto.randomBytes(TRANSFER_KEY_BYTES))
    }
}
