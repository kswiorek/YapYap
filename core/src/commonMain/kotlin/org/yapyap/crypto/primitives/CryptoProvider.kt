package org.yapyap.crypto.primitives

import org.yapyap.crypto.keys.EncryptionKeyPair
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.SigningKeyPair
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import kotlin.uuid.Uuid


internal interface CryptoProvider {
    suspend fun sha256(bytes: ByteArray): ByteArray

    fun randomBytes(size: Int): ByteArray

    suspend fun generateSigningKeyPair(): SigningKeyPair

    suspend fun generateEncryptionKeyPair(): EncryptionKeyPair

    suspend fun signDetached(privateKey: SigningPrivateKey, message: ByteArray): ByteArray

    suspend fun verifyDetached(publicKey: SigningPublicKey, message: ByteArray, signature: ByteArray): Boolean

    fun toHex(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }

    fun generateNonce(scheme: SignalSecurityScheme): ByteArray

    suspend fun accountIdFromPublicKey(accountSigningKey: SigningPublicKey): AccountId =
        AccountId(toHex(sha256(accountSigningKey.bytes)))

    suspend fun peerIdFromPublicKey(deviceSigningKey: SigningPublicKey): PeerId =
        PeerId(toHex(sha256(deviceSigningKey.bytes)))

    /**
     * Pure truncation: 32-byte (or longer) digest -> 128-bit [Uuid].
     * Raw bytes, no version/variant nibbles forced — the property is preimage
     * resistance, not RFC cosmetics (`kotlin.uuid` has no v5 constructor;
     * v5 is SHA-1 anyway). Non-suspend so tests can vector-check it
     * without a crypto backend.
     */
    fun uuidFromHash(digest: ByteArray): Uuid {
        require(digest.size >= Uuid.SIZE_BYTES) {
            "Digest must be at least ${Uuid.SIZE_BYTES} bytes but was ${digest.size}"
        }
        val bytes = digest.copyOf(Uuid.SIZE_BYTES)
        // RFC 4122 v4: version nibble + variant bits. Matches Uuid.random().
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return Uuid.fromByteArray(bytes)
    }

    /**
     * Generic `bytes -> Uuid`: `uuidFromHash(sha256(input))`.
     * Room genesis is the first consumer (`orchestrator.dag.RoomIds`);
     * content-addressed messageIds / packetIds become call-sites with their own
     * domain separation — no new crypto code.
     */
    suspend fun hashedUuid(input: ByteArray): Uuid =
        uuidFromHash(sha256(input))

    suspend fun deriveSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    suspend fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, outputLength: Int): ByteArray

    /** ChaCha20-Poly1305; returned bytes are `IV || ciphertext || tag` (library-managed IV). */
    suspend fun encryptAead(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray

    /** Inverse of [encryptAead]; expects the same `IV || ciphertext || tag` layout. */
    suspend fun decryptAead(key: ByteArray, ciphertext: ByteArray, associatedData: ByteArray? = null): ByteArray

    suspend fun privateSigningKeyToPublicKey(privateKey: SigningPrivateKey): SigningPublicKey

    suspend fun privateEncryptionKeyToPublicKey(privateKey: EncryptionPrivateKey): EncryptionPublicKey
}
