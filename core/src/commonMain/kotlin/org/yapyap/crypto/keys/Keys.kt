package org.yapyap.crypto.keys

import kotlin.jvm.JvmInline

/**
 * Raw key material with a type that says what it is. Equality compares content (a data class
 * over a bare [ByteArray] would compare identity), and private keys never print their bytes.
 * [bytes] is not copied: treat it as read-only.
 *
 * The public key and id types are `public` only because SQLDelight-generated row classes name
 * them; constructors and contents stay internal.
 */
public sealed class KeyBytes(internal val bytes: ByteArray) {
    final override fun equals(other: Any?): Boolean =
        this === other || (other != null && other::class == this::class && bytes.contentEquals((other as KeyBytes).bytes))

    final override fun hashCode(): Int = 31 * this::class.hashCode() + bytes.contentHashCode()
}

/** Ed25519 public key. */
public class SigningPublicKey internal constructor(bytes: ByteArray) : KeyBytes(bytes) {
    override fun toString(): String = "SigningPublicKey(${bytes.hexPrefix()})"
}

/** Ed25519 private key. */
internal class SigningPrivateKey(bytes: ByteArray) : KeyBytes(bytes) {
    override fun toString(): String = "SigningPrivateKey(redacted)"
}

/** X25519 public key. */
public class EncryptionPublicKey internal constructor(bytes: ByteArray) : KeyBytes(bytes) {
    override fun toString(): String = "EncryptionPublicKey(${bytes.hexPrefix()})"
}

/** X25519 private key. */
internal class EncryptionPrivateKey(bytes: ByteArray) : KeyBytes(bytes) {
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

/** Signed-prekey id (`yapyap:spk-<hex>`, wire-visible in X3DH). */
@JvmInline
public value class SignedPreKeyId internal constructor(internal val value: String) {
    init {
        require(value.isNotBlank()) { "SignedPreKeyId must not be blank" }
    }

    override fun toString(): String = value
}

/** One-time-prekey id (`yapyap:opk-<hex>`, wire-visible in 4-DH upgrade offers). */
@JvmInline
public value class OneTimePreKeyId internal constructor(internal val value: String) {
    init {
        require(value.isNotBlank()) { "OneTimePreKeyId must not be blank" }
    }

    override fun toString(): String = value
}

private fun ByteArray.hexPrefix(): String =
    take(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + "…"
