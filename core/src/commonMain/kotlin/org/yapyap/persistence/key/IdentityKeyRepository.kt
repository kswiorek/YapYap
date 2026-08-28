package org.yapyap.persistence.key

import org.yapyap.crypto.identity.*
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

interface IdentityKeyRepository {
    suspend fun getAccountRecord(accountId: AccountId): AccountIdentityRecord?

    suspend fun getDeviceRecord(deviceId: PeerId): DeviceIdentityRecord?

    suspend fun insertLocalDevice(accountId: AccountId, identity: DeviceIdentityRecord)

    suspend fun getLocalDeviceRecord(): DeviceIdentityRecord?

    suspend fun getLocalAccountRecord(): AccountIdentityRecord?

    suspend fun insertPeerDevice(accountId: AccountId, deviceType: DeviceType, identity: DeviceIdentityRecord, torEndpoint: TorEndpoint)

    suspend fun insertLocalAccount(identity: AccountIdentityRecord)

    suspend fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord?

    suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint?

    suspend fun insertPeerAccount(identity: AccountIdentityRecord, admin: Boolean, status: AccountStatus, displayName: String)

    suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId>

    suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId?

    suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)

    suspend fun getSignedPreKey(spkId: String): SignedPreKeyRecord?

    suspend fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKeyRecord?

    suspend fun insertSignedPreKey(spk: SignedPreKeyRecord)

    suspend fun upsertDeviceSignedPreKey(spk: SignedPreKeyRecord)
}
