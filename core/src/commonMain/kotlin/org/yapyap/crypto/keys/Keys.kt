package org.yapyap.crypto.keys

import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey

/**
 * Private key material with a type that says what it is. Equality compares content and the
 * bytes are never printed. [bytes] is not copied: treat it as read-only. The public halves are
 * wire types and live in `protocol.identity`.
 */
internal sealed class PrivateKey(internal val bytes: ByteArray) {
    final override fun equals(other: Any?): Boolean =
        this === other || (other != null && other::class == this::class && bytes.contentEquals((other as PrivateKey).bytes))

    final override fun hashCode(): Int = 31 * this::class.hashCode() + bytes.contentHashCode()
}

/** Ed25519 private key. */
internal class SigningPrivateKey(bytes: ByteArray) : PrivateKey(bytes) {
    override fun toString(): String = "SigningPrivateKey(redacted)"
}

/** X25519 private key. */
internal class EncryptionPrivateKey(bytes: ByteArray) : PrivateKey(bytes) {
    override fun toString(): String = "EncryptionPrivateKey(redacted)"
}

internal data class SigningKeyPair(
    val publicKey: SigningPublicKey,
    val privateKey: SigningPrivateKey,
)

internal data class EncryptionKeyPair(
    val publicKey: EncryptionPublicKey,
    val privateKey: EncryptionPrivateKey,
)
