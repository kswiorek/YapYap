package org.yapyap.protection.envelope

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import org.yapyap.protection.service.EnvelopeProtectContext

/**
 * Small-path file protection (design §4): per-transfer symmetric chunk AEAD plus
 * ratchet-sealed OFFER halves and REQUESTs, every FILE envelope signed by its author.
 *
 * Keys are threaded explicitly per call (the bootstrap pattern): the pusher owns its
 * `file_pushes` row key and passes it to [encryptChunk]; the inbound handler owns its
 * `file_incoming_transfers` row key and passes it to [decryptChunk]. Protection never
 * stores a key, and the orchestrator never sees one — routing calls these on plaintext
 * it read from `FilePlaintextSource`.
 */
internal interface FileProtection {
    /** Signs [input]; OFFER sealed halves must already be sealed via [sealOffer]. */
    suspend fun protect(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope

    /**
     * Verifies the author signature and decodes by kind. REQUEST bodies are opened
     * inline (ratchet-decrypted); OFFER sealed halves stay sealed — open them with
     * [openOffer]. CHUNK ciphertext is returned as-is; decrypt with [decryptChunk].
     */
    suspend fun open(input: FileEnvelope): OpenedFileEnvelope

    /** AEAD-encrypts one chunk under the transfer key, bound to (transferId, chunkIndex). */
    suspend fun encryptChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    /** Inverse of [encryptChunk]; fails when the chunk was bound to another index or transfer. */
    suspend fun decryptChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        chunk: FilePayload.EncryptedChunk,
    ): ByteArray

    /** Ratchet-seals an OFFER's sealed half to the target device (the key's only carrier). */
    suspend fun sealOffer(offer: FilePayload.SealedOffer, target: PeerId): ByteArray

    /** Inverse of [sealOffer]; a sealed-half replay fails here. */
    suspend fun openOffer(sealed: ByteArray, source: PeerId): FilePayload.SealedOffer

    /** Fresh per-transfer symmetric key (design §4): one key never outlives one transfer. */
    fun generateTransferKey(): ByteArray
}
