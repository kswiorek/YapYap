package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

interface IdentityResolver {
    fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord

    fun getLocalAccountIdentityRecord(): AccountIdentityRecord

    fun loadLocalPrivateKey(purpose: IdentityKeyPurpose): ByteArray

    fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord?

    fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint

    fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)
}