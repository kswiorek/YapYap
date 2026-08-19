package org.yapyap.crypto.identity

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.key.*
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.time.FixedEpochProvider
import kotlin.test.*

class DefaultIdentityOrchestrationTest {

    private val fixedTor = TorEndpoint(onionAddress = "fixture-identity.onion", port = 443)

    private fun stack(): Triple<
        InMemoryIdentityKeyRepository,
        InMemoryKeyStore,
        Pair<DefaultIdentityResolver, DefaultIdentityProvisioning>,
        > {
        val repo = InMemoryIdentityKeyRepository(defaultLocalTor = fixedTor)
        val store = InMemoryKeyStore()
        val crypto = DefaultCryptoProvider()
        val resolver = DefaultIdentityResolver(crypto, repo, store)
        val timeProvider = FixedEpochProvider(0L)
        val provisioning = DefaultIdentityProvisioning(crypto, repo, store, resolver, timeProvider)
        return Triple(repo, store, Pair(resolver, provisioning))
    }

    @Test
    fun provisioning_createAccount_then_createDevice_resolver_roundTrip() = runTest {
        val (_, _, triple) = stack()
        val (resolver, provisioning) = triple

        val account = provisioning.createNewAccountIdentity(displayName = "Local User")
        val device = provisioning.createNewDeviceIdentity()

        val resolvedAccount = resolver.getLocalAccountIdentityRecord()
        assertEquals(account.accountId, resolvedAccount.accountId)

        val resolvedDevice = resolver.getLocalDeviceIdentityRecord()
        assertEquals(device.deviceId, resolvedDevice.deviceId)
        assertEquals(device.signing.publicKey.contentHashCode(), resolvedDevice.signing.publicKey.contentHashCode())
        assertNotNull(resolvedDevice.signedPreKey)
        assertEquals(device.signedPreKey!!.keyId, resolvedDevice.signedPreKey.keyId)

        val localSpk = resolver.getCurrentLocalSignedPreKey()
        assertEquals(resolvedDevice.signedPreKey.keyId, localSpk.keyId)
        assertContentEquals(resolvedDevice.signedPreKey.publicKey, localSpk.publicKey)

        assertEquals(fixedTor, resolver.resolveTorEndpointForDevice(device.deviceId))
    }

    @Test
    fun resolver_recoversDeviceRecordFromKeystoreWhenDbRowMissing() = runTest {
        val (repo, store, triple) = stack()
        val (resolver, provisioning) = triple

        provisioning.createNewAccountIdentity(displayName = "Recovery User")
        val device = provisioning.createNewDeviceIdentity()

        repo.clearLocalDeviceRecord()

        val recovered = resolver.getLocalDeviceIdentityRecord()
        assertEquals(device.deviceId, recovered.deviceId)
        assertContentEquals(device.signing.publicKey, recovered.signing.publicKey)
        assertContentEquals(device.encryption.publicKey, recovered.encryption.publicKey)
        assertContentEquals(device.keySignature, recovered.keySignature)
        assertNotNull(resolver.resolvePeerIdentityRecord(device.deviceId))
    }

    @Test
    fun resolver_recoversDeviceRecordFromPrivateKeysOnlyWhenPublicKeysMissing() = runTest {
        val (repo, store, triple) = stack()
        val (resolver, provisioning) = triple

        provisioning.createNewAccountIdentity(displayName = "Private-only recovery")
        val device = provisioning.createNewDeviceIdentity()

        val signingKeyId = LOCAL_DEVICE_KEY_PREFIX + IdentityKeyPurpose.SIGNING.name.lowercase()
        val encryptionKeyId = LOCAL_DEVICE_KEY_PREFIX + IdentityKeyPurpose.ENCRYPTION.name.lowercase()
        store.deleteKey(KeyReference(signingKeyId, IdentityKeyPurpose.SIGNING, KeyType.PUBLIC))
        store.deleteKey(KeyReference(encryptionKeyId, IdentityKeyPurpose.ENCRYPTION, KeyType.PUBLIC))
        repo.clearLocalDeviceRecord()

        val recovered = resolver.getLocalDeviceIdentityRecord()
        assertEquals(device.deviceId, recovered.deviceId)
        assertContentEquals(device.signing.publicKey, recovered.signing.publicKey)
        assertContentEquals(device.encryption.publicKey, recovered.encryption.publicKey)
        assertContentEquals(device.keySignature, recovered.keySignature)
    }

    @Test
    fun resolver_recoversAccountRecordFromKeystoreWhenDbRowMissing() = runTest {
        val (repo, store, triple) = stack()
        val (resolver, provisioning) = triple

        val account = provisioning.createNewAccountIdentity(displayName = "Recovery User")
        repo.accounts.remove(account.accountId.id)
        repo.localAccount = null


        val recovered = resolver.getLocalAccountIdentityRecord()
        assertEquals(account.accountId, recovered.accountId)
        assertContentEquals(account.key!!.publicKey, recovered.key!!.publicKey)
    }

    @Test
    fun provisioning_provisionPeerDevice_then_resolveTor_and_listPeers() = runTest {
        val (repo, _, triple) = stack()
        val (resolver, provisioning) = triple

        val account = provisioning.createNewAccountIdentity("Acc")
        val localDevice = provisioning.createNewDeviceIdentity()

        val remoteSigning = DefaultCryptoProvider().generateSigningKeyPair()
        val remoteEncryption = DefaultCryptoProvider().generateEncryptionKeyPair()
        val remotePeer =
            DeviceIdentityRecord(
                deviceId = PeerId("peerdevidaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                signing = IdentityPublicKeyRecord("rk-s", 0L, IdentityKeyPurpose.SIGNING, remoteSigning.publicKey),
                encryption = IdentityPublicKeyRecord(
                    "rk-e",
                    0L,
                    IdentityKeyPurpose.ENCRYPTION,
                    remoteEncryption.publicKey
                ),
            )
        val peerTor = TorEndpoint(onionAddress = "peerfixture.onion", port = 995)

        provisioning.provisionDeviceIdentity(account.accountId, DeviceType.DESKTOP, remotePeer, peerTor)

        assertEquals(peerTor, resolver.resolveTorEndpointForDevice(remotePeer.deviceId))

        val peers = resolver.getAllPeerDevicesForAccount(account.accountId)
        assertTrue(peers.map { it.id }.toSet().contains(localDevice.deviceId.id))
        assertTrue(peers.map { it.id }.toSet().contains(remotePeer.deviceId.id))
    }

    @Test
    fun provisioning_provisionPeerAccount_persistsInRepository() = runTest {
        val (repo, _, triple) = stack()
        val (_, provisioning) = triple

        val signing = DefaultCryptoProvider().generateSigningKeyPair()
        val acc =
            AccountIdentityRecord(
                accountId = AccountId("external-acc-id"),
                displayName = "Peer Account",
                key = IdentityPublicKeyRecord("ext", 1L, IdentityKeyPurpose.SIGNING, signing.publicKey),
            )
        provisioning.provisionAccountIdentity(
            accountIdentity = acc,
            admin = false,
            status = AccountStatus.ACTIVE,
        )

        assertNotNull(repo.accounts["external-acc-id"])
        assertEquals(acc.accountId, repo.accounts["external-acc-id"]!!.accountId)
    }

    @Test
    fun provisioning_exportThenImportRecoveryKey_roundTripsAccount() = runTest {
        val (_, _, source) = stack()
        val (_, sourceProvisioning) = source

        val original = sourceProvisioning.createNewAccountIdentity(displayName = "Recover Me")
        val recoveryKey = sourceProvisioning.exportLocalAccountRecoveryKey()

        val (_, _, target) = stack()
        val (targetResolver, targetProvisioning) = target
        val imported = targetProvisioning.importLocalAccountFromRecovery(recoveryKey)

        assertEquals(original.accountId, imported.accountId)
        assertEquals(original.displayName, imported.displayName)
        assertContentEquals(original.key!!.publicKey, imported.key!!.publicKey)

        val resolved = targetResolver.getLocalAccountIdentityRecord()
        assertEquals(original.accountId, resolved.accountId)
        assertEquals("Recover Me", resolved.displayName)
        assertTrue(targetResolver.getLocalAccountPrivateKey(IdentityKeyPurpose.SIGNING).isNotEmpty())
    }

    @Test
    fun recoveryKeyCodec_rejectsMalformedInput() {
        assertFailsWith<org.yapyap.crypto.CryptoException.InvalidRecoveryKey> {
            AccountRecoveryKeyCodec.decode("not-a-recovery-key")
        }
        assertFailsWith<org.yapyap.crypto.CryptoException.InvalidRecoveryKey> {
            AccountRecoveryKeyCodec.decode("YYR1.only-one-part")
        }
    }

    @Test
    fun oneTimePreKeyStore_allocateThenConsume_onceOnly() = runTest {
        val crypto = DefaultCryptoProvider()
        val store = InMemoryOpkRepository(crypto)

        val opk = store.allocate()
        store.markOffered(opk.keyId)
        val consumed = store.consume(opk.keyId)
        assertNotNull(consumed)
        assertContentEquals(opk.publicKey, consumed.publicKey)
        assertEquals(null, store.consume(opk.keyId))
    }
}
