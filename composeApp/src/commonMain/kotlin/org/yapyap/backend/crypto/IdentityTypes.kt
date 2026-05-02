package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.PeerId

enum class IdentityKeyPurpose {
    SIGNING,
    ENCRYPTION,
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

data class DeviceIdentityRecord(
    val deviceId: PeerId,
    val signing: IdentityPublicKeyRecord,
    val encryption: IdentityPublicKeyRecord,
)

data class AccountIdentityRecord(
    val accountId: AccountId,
    val key: IdentityPublicKeyRecord,
)