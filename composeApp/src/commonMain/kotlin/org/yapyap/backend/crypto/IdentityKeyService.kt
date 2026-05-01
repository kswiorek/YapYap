package org.yapyap.backend.crypto

import org.yapyap.backend.db.AccountStatus
import org.yapyap.backend.db.DeviceType
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

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

interface IdentityPublicKeyRepository {
    fun getAccountPublicKey(accountId: AccountId): AccountIdentityRecord?

    fun getDevicePublicKey(deviceId: PeerId): DeviceIdentityRecord?

    fun insertLocalDevice(accountId: AccountId, identity: DeviceIdentityRecord)

    fun insertPeerDevice(accountId: AccountId, deviceType: DeviceType, identity: DeviceIdentityRecord, torEndpoint: TorEndpoint)

    fun insertLocalAccount(displayName: String, identity: AccountIdentityRecord)

    fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord?

    fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint
    fun insertPeerAccount(identity: AccountIdentityRecord, admin: Boolean, status: AccountStatus, displayName: String)

}

interface IdentityResolver {
    fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord

    fun getLocalAccountIdentityRecord(): AccountIdentityRecord

    fun loadLocalPrivateKey(purpose: IdentityKeyPurpose): ByteArray

    fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord?

    fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint
}

interface IdentityProvisioning {
    fun createNewDeviceIdentity(): DeviceIdentityRecord

    fun createNewAccountIdentity(displayName: String): AccountIdentityRecord

    fun provisionDeviceIdentity(accountId: AccountId, deviceIdentity: DeviceIdentityRecord, torEndpoint: TorEndpoint)

    fun provisionAccountIdentity(displayName: String, accountIdentity: AccountIdentityRecord, admin: Boolean, status: AccountStatus)
}