package org.yapyap.backend.crypto

import org.yapyap.backend.crypto.e2ee.X3dhRemotePeerKeys
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

interface IdentityResolver {
    suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord

    suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord

    suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray
    suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray

    suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord?

    fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint

    fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)

    suspend fun resolvePeerX3dhRemoteKeys(
        deviceId: PeerId,
        signedPreKeyId: String? = null,
    ): X3dhRemotePeerKeys

    suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey

    /** Resolves a local SPK by wire id (supports archived keys after rotation). */
    suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): LocalSignedPreKey
}