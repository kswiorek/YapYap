package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SEAL_KEY_BYTES
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.protocol.envelopes.FileSeal
import kotlin.coroutines.cancellation.CancellationException

internal class RatchetFileProtection(
    private val ratchet: RatchetSealer,
    private val crypto: CryptoProvider,
) : FileProtection {
    override suspend fun seal(seal: FileSeal, target: PeerId): ByteArray =
        ratchet.seal(seal.encode(), target, what = "file seal")

    override suspend fun openSeal(content: ByteArray, origin: PeerId): FileSeal {
        val plaintext = ratchet.open(content, origin, what = "file seal")
        return ratchet.decode("file seal") { FileSeal.decode(plaintext) }
    }

    override suspend fun encryptChunk(sealId: ContentId, key: SealKey, offset: Long, plaintext: ByteArray): ByteArray =
        try {
            crypto.encryptAead(key.bytes, plaintext, chunkAad(sealId, offset, plaintext.size))
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

    override suspend fun decryptChunk(sealId: ContentId, key: SealKey, offset: Long, ciphertext: ByteArray): ByteArray {
        val length = ciphertext.size - FileProtection.CHUNK_OVERHEAD_BYTES
        if (length <= 0) throw ProtectionException.InvalidEnvelope(IllegalArgumentException("chunk too short"))
        return try {
            crypto.decryptAead(key.bytes, ciphertext, chunkAad(sealId, offset, length))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.DECRYPTION_FAILED,
                message = "Failed to decrypt file chunk",
                fields = mapOf("sealId" to sealId, "offset" to offset, "error" to e.toString()),
            )
            throw ProtectionException.mapEncryptDecryptFailure(e)
        }
    }

    override suspend fun sealRequest(request: FileRequest, target: PeerId): ByteArray =
        ratchet.seal(request.encode(), target, what = "file REQUEST")

    override suspend fun openRequest(content: ByteArray, source: PeerId): FileRequest {
        val plaintext = ratchet.open(content, source, what = "file REQUEST")
        return ratchet.decode("file REQUEST") { FileRequest.decode(plaintext) }
    }

    override fun generateSealKey(): SealKey = SealKey(crypto.randomBytes(SEAL_KEY_BYTES))

    private fun chunkAad(sealId: ContentId, offset: Long, length: Int): ByteArray {
        val writer = ByteWriter(sealId.bytes.size + 12)
        writer.writeBytes(sealId.bytes)
        writer.writeLong(offset)
        writer.writeInt(length)
        return writer.toByteArray()
    }
}
