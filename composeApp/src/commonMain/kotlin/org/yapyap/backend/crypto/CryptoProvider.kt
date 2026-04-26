package org.yapyap.backend.crypto

interface CryptoProvider {
    fun sha256(bytes: ByteArray): ByteArray

    fun toHex(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }

    fun accountIdFromPublicKey(accountSigningPublicKey: ByteArray): String =
        toHex(sha256(accountSigningPublicKey))

    fun deviceIdFromPublicKey(deviceSigningPublicKey: ByteArray): String =
        toHex(sha256(deviceSigningPublicKey))
}
