package org.yapyap.backend.crypto

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

data class DeviceIdentityRecord(
    val deviceId: String,
    val signing: IdentityPublicKeyRecord,
    val encryption: IdentityPublicKeyRecord,
)

data class AccountIdentityRecord(
    val accountId: String,
    val key: IdentityPublicKeyRecord,
)

interface IdentityPublicKeyRepository {
    fun getAccountPublicKey(accountId: String): AccountIdentityRecord?

    fun getDevicePublicKey(deviceId: String): DeviceIdentityRecord?

    fun insertLocalDevice(accountId: String, identity: DeviceIdentityRecord)

    fun insertLocalAccount(displayName: String, identity: AccountIdentityRecord)

    fun resolveDeviceKey(deviceId: String, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord?
}

interface IdentityResolver {
    fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord

    fun getLocalAccountIdentityRecord(): AccountIdentityRecord

    fun loadLocalPrivateKey(purpose: IdentityKeyPurpose): ByteArray

    fun resolvePeerIdentityRecord(deviceId: String): DeviceIdentityRecord?
}

interface IdentityProvisioning {
    fun createNewDeviceIdentity(): DeviceIdentityRecord

    fun createNewAccountIdentity(displayName: String): AccountIdentityRecord
}