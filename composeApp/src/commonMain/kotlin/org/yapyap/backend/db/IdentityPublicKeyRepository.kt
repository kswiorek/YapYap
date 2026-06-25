package org.yapyap.backend.db

import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.AccountIdentityRecord
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.crypto.SignedPreKeyRecord
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

interface IdentityPublicKeyRepository {
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
