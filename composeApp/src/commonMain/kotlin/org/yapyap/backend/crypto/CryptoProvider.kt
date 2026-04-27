package org.yapyap.backend.crypto


interface CryptoProvider {
    fun sha256(bytes: ByteArray): ByteArray

    fun generateSigningKeyPair(): SigningKeyPair

    fun generateEncryptionKeyPair(): EncryptionKeyPair

    fun signDetached(privateSigningKey: ByteArray, message: ByteArray): ByteArray

    fun verifyDetached(publicSigningKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    fun toHex(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }

    fun accountIdFromPublicKey(accountSigningPublicKey: ByteArray): String =
        toHex(sha256(accountSigningPublicKey))

    fun idFromPublicKey(deviceSigningPublicKey: ByteArray): String =
        toHex(sha256(deviceSigningPublicKey))
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

    fun signDetached(message: ByteArray): ByteArray

    fun verifyDetached(deviceId: String, message: ByteArray, signature: ByteArray): Boolean
}
