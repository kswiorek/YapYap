package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.protocol.envelopes.FileSeal

/**
 * FILE content protection (docs/envelope-redesign.md §5), handed only to file routing:
 *
 * - **Seals** go through the Double Ratchet, from the origin to the seal's target device — one
 *   frame per seal, however many hops carry it. The seal is the only carrier of its [SealKey].
 * - **Chunks** never touch the ratchet: ChaCha20-Poly1305 under the seal key with
 *   AAD = (`sealId`, `offset`, `length`). The AAD binds a chunk to its seal and its byte range, so
 *   a relay can neither move, splice nor re-label one, and transfers of one seal may use different
 *   chunkings.
 * - **REQUESTs** are ratchet-encrypted to the asked holder.
 *
 * Keys are threaded per call; protection never stores one. Crypto failures leave as
 * [org.yapyap.protection.ProtectionException] verdicts.
 */
internal interface FileProtection {
    suspend fun seal(seal: FileSeal, target: PeerId): ByteArray

    /** Opens a seal [origin] made for us; a seal opened before is a Duplicate. */
    suspend fun openSeal(content: ByteArray, origin: PeerId): FileSeal

    suspend fun encryptChunk(sealId: ContentId, key: SealKey, offset: Long, plaintext: ByteArray): ByteArray

    /** Inverse of [encryptChunk]; any other seal, offset or length fails authentication. */
    suspend fun decryptChunk(sealId: ContentId, key: SealKey, offset: Long, ciphertext: ByteArray): ByteArray

    suspend fun sealRequest(request: FileRequest, target: PeerId): ByteArray

    suspend fun openRequest(content: ByteArray, source: PeerId): FileRequest

    fun generateSealKey(): SealKey

    companion object {
        /** Bytes [encryptChunk] adds to a chunk's plaintext (the AEAD IV and tag). */
        const val CHUNK_OVERHEAD_BYTES: Int = DefaultCryptoProvider.AEAD_OVERHEAD_BYTES
    }
}
