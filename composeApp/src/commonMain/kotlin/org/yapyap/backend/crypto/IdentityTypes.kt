package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.PeerId

enum class IdentityKeyPurpose {
    SIGNING,
    ENCRYPTION,
    SIGNED_PREKEY,
}

data class IdentityPublicKeyRecord(
    val keyId: String,
    val keyVersion: Long,
    val purpose: IdentityKeyPurpose,
    val publicKey: ByteArray,
)

data class AccountId(
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "AccountId cannot be blank" }
    }
}

data class SignedPreKeyRecord(
    val keyId: String,
    val publicKey: ByteArray,
    val signature: ByteArray,
    val privateKey: ByteArray?,
    val deviceId: PeerId,
    val isActive: Boolean = true,
    val createdAtEpochSeconds: Long? = null,
) {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
        require(signature.isNotEmpty()) { "signature must not be empty" }
    }
}

/** One-time prekey allocated locally and offered to a peer for 4-DH upgrade. */
data class LocalOneTimePreKey(
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

data class DeviceIdentityRecord(
    val deviceId: PeerId,
    val signing: IdentityPublicKeyRecord,
    val encryption: IdentityPublicKeyRecord,
    val signedPreKey: SignedPreKeyRecord? = null,
    val keySignature: ByteArray? = null,
)

data class AccountIdentityRecord(
    val accountId: AccountId,
    val key: IdentityPublicKeyRecord,
)
