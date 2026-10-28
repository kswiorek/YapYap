package org.yapyap.crypto.identity

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.key.InMemoryIdentityKeyRepository
import org.yapyap.persistence.key.InMemoryKeyStore
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import kotlin.test.*

/**
 * Peer X3DH key verification: the resolver still verifies the signed-prekey signature
 * against the device signing key (chain authenticity itself is the fold's job).
 */
class PeerX3dhKeysVerificationTest {
    @Test
    fun resolvePeerIdentityRecord_succeedsForStoredPeer() = runTest {
        val crypto = DefaultCryptoProvider()
        val repo = InMemoryIdentityKeyRepository()
        val store = InMemoryKeyStore()
        val resolver = DefaultIdentityResolver(crypto, repo, store)
        val accountId = AccountId("x3dh-account")
        repo.insertLocalAccount(
            identity = AccountIdentityRecord(
                accountId = accountId,
                displayName = "X3DH User",
                key = IdentityPublicKeyRecord(IdentityKeyPurpose.SIGNING, byteArrayOf(0x01)),
            )
        )
        val peerTor = TorEndpoint(onionAddress = "peer-x3dh.onion", port = 443)

        val peer = buildAttestedDeviceIdentity(crypto, "peer-valid")
        repo.insertPeerDevice(accountId, DeviceType.DESKTOP, peer, peerTor)

        val resolved = resolver.resolvePeerIdentityRecord(peer.deviceId)
        assertNotNull(resolved)
        assertEquals(peer.deviceId, resolved.deviceId)
        assertContentEquals(peer.encryption.publicKey, resolved.encryption.publicKey)
    }

    @Test
    fun resolvePeerX3dhRemoteKeys_rejectsTamperedSpk() = runTest {
        val crypto = DefaultCryptoProvider()
        val repo = InMemoryIdentityKeyRepository()
        val store = InMemoryKeyStore()
        val resolver = DefaultIdentityResolver(crypto, repo, store)
        val accountId = AccountId("x3dh-account")
        repo.insertLocalAccount(
            identity = AccountIdentityRecord(
                accountId = accountId,
                displayName = "X3DH User",
                key = IdentityPublicKeyRecord(IdentityKeyPurpose.SIGNING, byteArrayOf(0x01)),
            )
        )
        val peerTor = TorEndpoint(onionAddress = "peer-x3dh.onion", port = 443)

        val validPeer = buildAttestedDeviceIdentity(crypto, "peer-valid")
        repo.insertPeerDevice(accountId, DeviceType.DESKTOP, validPeer, peerTor)

        val tamperedSpk = validPeer.copy(
            signedPreKey = validPeer.signedPreKey!!.copy(
                keyId = "spk-peer-valid-tampered",
                signature = validPeer.signedPreKey.signature.copyOf().also {
                    it[0] = (it[0].toInt() xor 0xff).toByte()
                },
            ),
        )
        val tamperedSpkPeerId = PeerId("${validPeer.deviceId.id}-spk-tamper")
        val tamperedSpkDevice = tamperedSpk.copy(deviceId = tamperedSpkPeerId)
        repo.insertPeerDevice(accountId, DeviceType.DESKTOP, tamperedSpkDevice, peerTor)

        assertNotNull(resolver.resolvePeerIdentityRecord(tamperedSpkPeerId))
        assertFailsWith<CryptoException.IncompleteRecord> {
            resolver.resolvePeerX3dhRemoteKeys(tamperedSpkPeerId)
        }

        val remoteKeys = resolver.resolvePeerX3dhRemoteKeys(validPeer.deviceId)
        assertContentEquals(validPeer.encryption.publicKey, remoteKeys.identityEncryptionPublicKey)
        assertContentEquals(validPeer.signedPreKey.publicKey, remoteKeys.signedPreKeyPublicKey)
    }
}
