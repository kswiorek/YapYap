package org.yapyap.crypto.identity

import org.yapyap.crypto.e2ee.session.X3dhRemotePeerKeys
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

interface IdentityResolver {
    suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord

    suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord

    suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray

    suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray

    suspend fun getLocalDeviceId(): PeerId

    suspend fun getLocalAccountId(): AccountId

    suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord

    suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint

    suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> {
        if (accountIds.isEmpty()) return emptyList()
        return accountIds.flatMap { getAllPeerDevicesForAccount(it) }
    }

    suspend fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)

    suspend fun resolvePeerX3dhRemoteKeys(
        deviceId: PeerId,
        signedPreKeyId: String? = null,
    ): X3dhRemotePeerKeys

    suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord

    /** Resolves a local SPK by wire id (supports archived keys after rotation). */
    suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord
}