package org.yapyap.backend.crypto

import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.yapyap.backend.db.DeviceType
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.time.FixedEpochSecondsProvider

class DeviceIdentityAttestationTest {

    private val config = IdentityKeyServiceConfig(
        defaultOnionAddress = "attestation-test.onion",
        defaultOnionPort = 443L,
    )

    private fun resolverStack(): Triple<
        InMemoryIdentityPublicKeyRepository,
        InMemoryKeyStore,
        Pair<DefaultIdentityResolver, DefaultIdentityProvisioning>,
    > {
        val repo = InMemoryIdentityPublicKeyRepository()
        val store = InMemoryKeyStore()
        val crypto = KmpCryptoProvider()
        val resolver = DefaultIdentityResolver(crypto, repo, store, config)
        val timeProvider = FixedEpochSecondsProvider(0L)
        val provisioning = DefaultIdentityProvisioning(crypto, repo, store, config, resolver, timeProvider)
        return Triple(repo, store, resolver to provisioning)
    }

    @Test
    fun resolvePeerIdentityRecord_succeedsAfterProvisioningRoundTrip() = runTest {
        val (repo, _, pair) = resolverStack()
        val (resolver, provisioning) = pair

        provisioning.createNewAccountIdentity(displayName = "Attestation User")
        val device = provisioning.createNewDeviceIdentity()

        val loaded = repo.getDeviceRecord(device.deviceId)
        assertNotNull(loaded?.keySignature)
        assertContentEquals(device.keySignature, loaded.keySignature)

        val resolved = resolver.resolvePeerIdentityRecord(device.deviceId)
        assertNotNull(resolved)
        assertContentEquals(device.keySignature, resolved.keySignature)
        assertEquals(device.deviceId, resolved.deviceId)
    }

    @Test
    fun resolvePeerX3dhRemoteKeys_rejectsTamperedAttestation() = runTest {
        val crypto = KmpCryptoProvider()
        val repo = InMemoryIdentityPublicKeyRepository()
        val store = InMemoryKeyStore()
        val resolver = DefaultIdentityResolver(crypto, repo, store, config)
        val accountId = AccountId("attestation-account")
        repo.insertLocalAccount(displayName = "Peer", identity = AccountIdentityRecord(
            accountId = accountId,
            key = IdentityPublicKeyRecord("acc-signing", 0, IdentityKeyPurpose.SIGNING, byteArrayOf(0x01)),
        ))
        val peerTor = TorEndpoint(onionAddress = "peer-attest.onion", port = 443)

        val validPeer = buildAttestedDeviceIdentity(crypto, "peer-valid")
        repo.insertPeerDevice(accountId, DeviceType.DESKTOP, validPeer, peerTor)

        val tamperedKeyPeerId = PeerId("${validPeer.deviceId.id}-key-tamper")
        val tamperedKeySignature = validPeer.copy(
            deviceId = tamperedKeyPeerId,
            keySignature = validPeer.keySignature!!.copyOf().also {
                it[0] = (it[0].toInt() xor 0xff).toByte()
            },
        )
        repo.insertPeerDevice(accountId, DeviceType.DESKTOP, tamperedKeySignature, peerTor)

        assertNull(resolver.resolvePeerIdentityRecord(tamperedKeyPeerId))
        assertFailsWith<IllegalStateException> {
            resolver.resolvePeerX3dhRemoteKeys(tamperedKeyPeerId)
        }

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
        assertFailsWith<IllegalArgumentException> {
            resolver.resolvePeerX3dhRemoteKeys(tamperedSpkPeerId)
        }

        val remoteKeys = resolver.resolvePeerX3dhRemoteKeys(validPeer.deviceId)
        assertContentEquals(validPeer.encryption.publicKey, remoteKeys.identityEncryptionPublicKey)
        assertContentEquals(validPeer.signedPreKey.publicKey, remoteKeys.signedPreKeyPublicKey)
    }
}
