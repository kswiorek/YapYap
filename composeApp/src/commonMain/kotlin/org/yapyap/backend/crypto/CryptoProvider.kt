package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.DeviceAddress

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

interface SignatureProvider {
    fun resolveLocalSigningKeyId(): String

    fun signDetached(keyId: String, message: ByteArray): ByteArray

    fun verifyDetached(source: DeviceAddress, message: ByteArray, signature: ByteArray): Boolean
}
