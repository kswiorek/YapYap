package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.TransferKey
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import kotlin.time.Instant

/**
 * Narrow file protection handed only to file routing (design §4): per-transfer chunk AEAD
 * plus ratchet-sealed OFFER halves and REQUESTs, every FILE envelope signed by its author.
 * Keys are [TransferKey] throughout; protection never stores one.
 */
internal interface FileEnvelopeProtection {
    /** Signs [input]. An OFFER is built with [protectOffer], which seals its key-carrying half. */
    suspend fun protect(input: FilePayload, context: FileProtectContext): FileEnvelope

    /**
     * Verifies the author signature and decodes by kind. REQUEST bodies are opened
     * inline (ratchet-decrypted); OFFER sealed halves stay sealed — open them with
     * [openOffer]. CHUNK ciphertext is returned as-is; decrypt with [decryptChunk].
     */
    suspend fun open(envelope: FileEnvelope): OpenedFileEnvelope

    /**
     * Seals and signs an OFFER in one call: the sealed half carries [sealed] plus [key],
     * ratchet-sealed to [ctx] target, wrapped in the public half.
     */
    suspend fun protectOffer(
        publicHalf: FileOfferPublicHalf,
        sealed: FileOfferSealedManifest,
        key: TransferKey,
        ctx: FileProtectContext,
    ): FileEnvelope

    /** AEAD-encrypts one chunk under the transfer key, bound to (transferId, chunkIndex). */
    suspend fun encryptChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: TransferKey,
        plaintext: ByteArray,
    ): ByteArray

    /**
     * Inverse of [encryptChunk]; the index comes from [chunk] only, so a replay under
     * another index fails authentication.
     */
    suspend fun decryptChunk(
        transferId: TransferId,
        key: TransferKey,
        chunk: FilePayload.EncryptedChunk,
    ): ByteArray

    /**
     * Opens the sealed half of an OFFER built by [protectOffer]. A redelivery of the same sealed
     * bytes opens again only for a few minutes (our own Defer after opening); after that it fails
     * as a ratchet replay (PERMANENT).
     */
    suspend fun openOffer(sealed: ByteArray, source: PeerId): FilePayload.SealedOffer

    /** Fresh per-transfer symmetric key (design §4): one key never outlives one transfer. */
    fun generateTransferKey(): TransferKey

    companion object {
        /** Bytes [encryptChunk] adds to a chunk's plaintext (the AEAD IV and tag). */
        const val CHUNK_OVERHEAD_BYTES: Int = DefaultCryptoProvider.AEAD_OVERHEAD_BYTES
    }
}

/** OFFER public half (relays read this): chunking and covered suffix (design §3.3–§3.4). */
internal data class FileOfferPublicHalf(
    val chunkSizeBytes: Int,
    val totalBytes: Long,
    val startIndex: Int,
)

/** Sealed OFFER content without the key: identity and display metadata (design §3.3). */
internal data class FileOfferSealedManifest(
    val fileId: FileId,
    val blockSize: Int,
    val blockHashes: List<ByteArray>,
    val fileName: String,
    val mimeType: String?,
)

/** File protection context: author, target, time and the transfer being protected. */
internal data class FileProtectContext(
    val sourceDeviceId: PeerId,
    val targetDeviceId: PeerId,
    val createdAt: Instant,
    val transferId: TransferId,
)
