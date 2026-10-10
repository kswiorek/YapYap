package org.yapyap.crypto.identity

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import kotlin.time.Instant

internal enum class IdentityKeyPurpose {
    SIGNING,
    ENCRYPTION,
    SIGNED_PREKEY,

    /** One-time bootstrap secret (symmetric, not an identity key — keystore namespacing only). */
    BOOTSTRAP_SECRET,
}

internal data class IdentityPublicKeyRecord(
    val keyId: String,
    val keyVersion: Long,
    val purpose: IdentityKeyPurpose,
    val publicKey: ByteArray,
)

internal data class SignedPreKeyRecord(
    val keyId: String,
    val publicKey: ByteArray,
    val signature: ByteArray,
    val privateKey: ByteArray?,
    val deviceId: PeerId,
    val isActive: Boolean = true,
    val createdAt: Instant? = null,
) {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
        require(signature.isNotEmpty()) { "signature must not be empty" }
    }
}

/** One-time prekey allocated locally and offered to a peer for 4-DH upgrade. */
internal data class LocalOneTimePreKey(
    val keyId: String,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
        require(privateKey.isNotEmpty()) { "privateKey must not be empty" }
    }
}

internal data class DeviceIdentityRecord(
    val deviceId: PeerId,
    val signing: IdentityPublicKeyRecord,
    val encryption: IdentityPublicKeyRecord,
    val signedPreKey: SignedPreKeyRecord? = null,
    val keySignature: ByteArray? = null,
)

internal data class AccountIdentityRecord(
    val accountId: AccountId,
    val displayName: String,
    val key: IdentityPublicKeyRecord? = null,
)

/** Single keystore/DB key namespace for all YapYap-held keys. All key IDs below derive from this. */
internal const val YAPYAP_KEY_PREFIX: String = "yapyap:"

internal const val LOCAL_DEVICE_KEY_PREFIX: String = YAPYAP_KEY_PREFIX + "local_device:"
internal const val LOCAL_ACCOUNT_KEY_PREFIX: String = YAPYAP_KEY_PREFIX + "local_account:"

/** Signed-prekey IDs (`yapyap:spk-<hex>`, wire-visible in X3DH). */
internal const val SPK_KEY_PREFIX: String = YAPYAP_KEY_PREFIX + "spk-"

/** One-time-prekey IDs (`yapyap:opk-<hex>`, wire-visible in 4-DH upgrade offers). */
internal const val OPK_KEY_PREFIX: String = YAPYAP_KEY_PREFIX + "opk-"

/** SQLCipher master-key ID (keystore only, never on the wire). */
internal const val MASTER_KEY_ID: String = YAPYAP_KEY_PREFIX + "db-master-key"