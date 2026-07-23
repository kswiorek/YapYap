package org.yapyap.crypto.identity

import org.yapyap.crypto.CryptoException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Versioned recovery-code codec: display name + account signing private key.
 *
 * Format: `YYR1.<url-safe-b64(displayNameUtf8)>.<url-safe-b64(privateKey)>`
 *
 * Admin status is intentionally omitted — mesh/roster is authoritative.
 */
@OptIn(ExperimentalEncodingApi::class)
object AccountRecoveryKeyCodec {
    private const val PREFIX = "YYR1"
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun encode(displayName: String, privateSigningKey: ByteArray): String {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(privateSigningKey.isNotEmpty()) { "privateSigningKey must not be empty" }
        return "$PREFIX.${b64.encode(displayName.encodeToByteArray())}.${b64.encode(privateSigningKey)}"
    }

    fun decode(recoveryKey: String): AccountRecoveryMaterial {
        val trimmed = recoveryKey.trim()
        val parts = trimmed.split('.')
        if (parts.size != 3 || parts[0] != PREFIX) {
            throw CryptoException.InvalidRecoveryKey("Expected $PREFIX.<displayName>.<privateKey>")
        }
        val displayName = runCatching { b64.decode(parts[1]).decodeToString() }
            .getOrElse { throw CryptoException.InvalidRecoveryKey("Invalid displayName encoding") }
        val privateKey = runCatching { b64.decode(parts[2]) }
            .getOrElse { throw CryptoException.InvalidRecoveryKey("Invalid private key encoding") }
        if (displayName.isBlank()) {
            throw CryptoException.InvalidRecoveryKey("displayName must not be blank")
        }
        if (privateKey.isEmpty()) {
            throw CryptoException.InvalidRecoveryKey("private key must not be empty")
        }
        return AccountRecoveryMaterial(displayName = displayName, privateSigningKey = privateKey)
    }
}

data class AccountRecoveryMaterial(
    val displayName: String,
    val privateSigningKey: ByteArray,
)
