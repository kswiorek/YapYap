package org.yapyap.backend.crypto

import org.yapyap.backend.crypto.e2ee.X3dhRemotePeerKeys
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

interface IdentityResolver {
    suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord

    suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord

    suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray
    suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray

    fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord?

    fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint

    fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)

    /** Published signed prekey for [deviceId] matching [signedPreKeyId] from roster gossip. */
    fun resolvePeerSignedPreKey(deviceId: PeerId, signedPreKeyId: String): SignedPreKeyRecord? {
        val signedPreKey = resolvePeerIdentityRecord(deviceId)?.signedPreKey ?: return null
        return signedPreKey.takeIf { it.keyId == signedPreKeyId }
    }

    /** Remote device material for X3DH initiator handshake. */
    fun resolvePeerX3dhRemoteKeys(deviceId: PeerId, signedPreKeyId: String): X3dhRemotePeerKeys {
        val device = resolvePeerIdentityRecord(deviceId)
            ?: error("Missing peer identity record for deviceId=$deviceId")
        val signedPreKey = resolvePeerSignedPreKey(deviceId, signedPreKeyId)
            ?: error("Missing signed prekey id=$signedPreKeyId for deviceId=$deviceId")
        return X3dhRemotePeerKeys(
            identityEncryptionPublicKey = device.encryption.publicKey,
            signedPreKeyPublicKey = signedPreKey.publicKey,
            signedPreKeyId = signedPreKey.keyId,
        )
    }

    /** Latest published SPK for [deviceId] (first contact when wire id is not yet known). */
    fun resolvePeerLatestX3dhRemoteKeys(deviceId: PeerId): X3dhRemotePeerKeys {
        val device = resolvePeerIdentityRecord(deviceId)
            ?: error("Missing peer identity record for deviceId=$deviceId")
        val signedPreKey = device.signedPreKey
            ?: error("Missing signed prekey on roster for deviceId=$deviceId")
        return X3dhRemotePeerKeys(
            identityEncryptionPublicKey = device.encryption.publicKey,
            signedPreKeyPublicKey = signedPreKey.publicKey,
            signedPreKeyId = signedPreKey.keyId,
        )
    }

    /** Current local signed prekey (private + public) used for X3DH responder paths. */
    suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey
}