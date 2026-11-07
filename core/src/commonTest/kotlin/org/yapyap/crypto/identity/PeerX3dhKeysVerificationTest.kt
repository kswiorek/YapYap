package org.yapyap.crypto.identity


import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.key.InMemoryIdentityStore
import org.yapyap.persistence.key.InMemoryKeyStore
import org.yapyap.persistence.key.TestIdentityStack
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.protocol.identity.SigningPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Peer X3DH key verification: the resolver still verifies the signed-prekey signature
 * against the device signing key (chain authenticity itself is the fold's job).
 */
class PeerX3dhKeysVerificationTest {
    @Test
    fun resolvePeerIdentityRecord_succeedsForStoredPeer() = runTest {
        val crypto = DefaultCryptoProvider()
        val repo = InMemoryIdentityStore()
        val store = InMemoryKeyStore()
        val resolver = TestIdentityStack(crypto, repo, store)
        val accountId = AccountId("x3dh-account")
        repo.insertLocalAccount(
            identity = AccountIdentity(
                accountId = accountId,
                displayName = "X3DH User",
                signingKey = SigningPublicKey(byteArrayOf(0x01)),
            )
        )
        val peerTor = TorEndpoint(onionAddress = "peer-x3dh.onion", port = 443)

        val peer = buildAttestedDeviceIdentity(crypto, "peer-valid")
        repo.insertPeerDevice(accountId, DeviceType.DESKTOP, peer, peerTor)

        val resolved = resolver.resolvePeerIdentity(peer.deviceId)
        assertNotNull(resolved)
        assertEquals(peer.deviceId, resolved.deviceId)
        assertEquals(peer.encryptionKey, resolved.encryptionKey)
    }

    @Test
    fun resolvePeerPreKeyBundle_rejectsTamperedSpk() = runTest {
        val crypto = DefaultCryptoProvider()
        val repo = InMemoryIdentityStore()
        val store = InMemoryKeyStore()
        val resolver = TestIdentityStack(crypto, repo, store)
        val accountId = AccountId("x3dh-account")
        repo.insertLocalAccount(
            identity = AccountIdentity(
                accountId = accountId,
                displayName = "X3DH User",
                signingKey = SigningPublicKey(byteArrayOf(0x01)),
            )
        )
        val peerTor = TorEndpoint(onionAddress = "peer-x3dh.onion", port = 443)

        val validPeer = buildAttestedDeviceIdentity(crypto, "peer-valid")
        repo.insertPeerDevice(accountId, DeviceType.DESKTOP, validPeer, peerTor)

        val validSpk = validPeer.signedPreKey!!
        val tamperedSpkPeerId = PeerId("${validPeer.deviceId.id}-spk-tamper")
        val tamperedSpkDevice = validPeer.copy(
            deviceId = tamperedSpkPeerId,
            signedPreKey = SignedPreKey(
                id = SignedPreKeyId("spk-peer-valid-tampered"),
                deviceId = tamperedSpkPeerId,
                publicKey = validSpk.publicKey,
                signature = validSpk.signature.copyOf().also {
                    it[0] = (it[0].toInt() xor 0xff).toByte()
                },
            ),
        )
        repo.insertPeerDevice(accountId, DeviceType.DESKTOP, tamperedSpkDevice, peerTor)

        assertNotNull(resolver.resolvePeerIdentity(tamperedSpkPeerId))
        assertFailsWith<CryptoException.IncompleteRecord> {
            resolver.resolvePeerPreKeyBundle(tamperedSpkPeerId)
        }

        val bundle = resolver.resolvePeerPreKeyBundle(validPeer.deviceId)
        assertEquals(validPeer.encryptionKey, bundle.identityKey)
        assertEquals(validSpk.publicKey, bundle.signedPreKey.publicKey)
    }
}
