package org.yapyap.backend.db

import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.AccountIdentityRecord
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

interface IdentityPublicKeyRepository {
    fun getAccountPublicKey(accountId: AccountId): AccountIdentityRecord?

    fun getDevicePublicKey(deviceId: PeerId): DeviceIdentityRecord?

    fun insertLocalDevice(accountId: AccountId, identity: DeviceIdentityRecord)

    fun insertPeerDevice(accountId: AccountId, deviceType: DeviceType, identity: DeviceIdentityRecord, torEndpoint: TorEndpoint)

    fun insertLocalAccount(displayName: String, identity: AccountIdentityRecord)

    fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord?

    fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint
    fun insertPeerAccount(identity: AccountIdentityRecord, admin: Boolean, status: AccountStatus, displayName: String)

    fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)

}