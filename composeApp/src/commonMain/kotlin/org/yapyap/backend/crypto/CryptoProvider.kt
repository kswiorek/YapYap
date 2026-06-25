package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme


interface CryptoProvider {
    suspend fun sha256(bytes: ByteArray): ByteArray

    fun randomBytes(size: Int): ByteArray

    suspend fun generateSigningKeyPair(): SigningKeyPair

    suspend fun generateEncryptionKeyPair(): EncryptionKeyPair

    suspend fun signDetached(privateSigningKey: ByteArray, message: ByteArray): ByteArray

    suspend fun verifyDetached(publicSigningKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    fun toHex(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }

    fun generateNonce(scheme: SignalSecurityScheme): ByteArray

    suspend fun accountIdFromPublicKey(accountSigningPublicKey: ByteArray): AccountId =
        AccountId(toHex(sha256(accountSigningPublicKey)))

    suspend fun peerIdFromPublicKey(deviceSigningPublicKey: ByteArray): PeerId =
        PeerId(toHex(sha256(deviceSigningPublicKey)))

    suspend fun deriveSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    suspend fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, outputLength: Int): ByteArray

    /** ChaCha20-Poly1305; returned bytes are `IV || ciphertext || tag` (library-managed IV). */
    suspend fun encryptAead(key: ByteArray, plaintext: ByteArray): ByteArray

    /** Inverse of [encryptAead]; expects the same `IV || ciphertext || tag` layout. */
    suspend fun decryptAead(key: ByteArray, ciphertext: ByteArray): ByteArray

    suspend fun privateSigningKeyToPublicKey(privateKey: ByteArray): ByteArray

    suspend fun privateEncryptionKeyToPublicKey(privateKey: ByteArray): ByteArray
}

data class SigningKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

data class EncryptionKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

interface SignatureProvider {
    suspend fun sign(message: ByteArray): ByteArray

    suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean
}
