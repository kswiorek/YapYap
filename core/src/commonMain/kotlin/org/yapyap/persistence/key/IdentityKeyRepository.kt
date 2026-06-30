package org.yapyap.persistence.key

import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.crypto.identity.SignedPreKeyRecord
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

interface IdentityKeyRepository {
    fun getAccountPublicKey(accountId: AccountId): AccountIdentityRecord?

    fun getDeviceRecord(deviceId: PeerId): DeviceIdentityRecord?

    fun insertLocalDevice(accountId: AccountId, identity: DeviceIdentityRecord)

    fun getLocalDeviceRecord(): DeviceIdentityRecord?

    fun insertPeerDevice(accountId: AccountId, deviceType: DeviceType, identity: DeviceIdentityRecord, torEndpoint: TorEndpoint)

    fun insertLocalAccount(displayName: String, identity: AccountIdentityRecord)

    fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord?

    fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint
    fun insertPeerAccount(identity: AccountIdentityRecord, admin: Boolean, status: AccountStatus, displayName: String)

    fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)

    fun getSignedPreKey(spkId: String): SignedPreKeyRecord?

    fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKeyRecord?

    fun insertSignedPreKey(spk: SignedPreKeyRecord)

    fun upsertDeviceSignedPreKey(spk: SignedPreKeyRecord)
}
