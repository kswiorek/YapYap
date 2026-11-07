package org.yapyap.crypto.identity

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.key.InMemoryIdentityStore
import org.yapyap.persistence.key.InMemoryKeyStore
import org.yapyap.persistence.key.InMemoryOpkRepository
import org.yapyap.persistence.key.TestIdentityStack
import org.yapyap.protocol.*
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.*

class DefaultIdentityOrchestrationTest {

    private val fixedTor = TorEndpoint(onionAddress = "fixture-identity.onion", port = 443)

    private fun stack(): Triple<
            InMemoryIdentityStore,
            InMemoryKeyStore,
            Pair<TestIdentityStack, DefaultIdentityProvisioning>,
            > {
        val repo = InMemoryIdentityStore(defaultLocalTor = fixedTor)
        val store = InMemoryKeyStore()
        val crypto = DefaultCryptoProvider()
        val resolver = TestIdentityStack(crypto, repo, store)
        val clock = FakeClock(epochSeconds(0L))
        val provisioning = DefaultIdentityProvisioning(crypto, repo, store, resolver, resolver, clock)
        return Triple(repo, store, Pair(resolver, provisioning))
    }

    @Test
    fun provisioning_createAccount_then_createDevice_resolver_roundTrip() = runTest {
        val (repo, store, triple) = stack()
        val (resolver, provisioning) = triple

        val account = provisioning.createNewAccountIdentity(displayName = "Local User")
        val device = provisioning.createNewDeviceIdentity()

        val resolvedAccount = resolver.getLocalAccountIdentity()
        assertEquals(account.accountId, resolvedAccount.accountId)

        val resolvedDevice = resolver.getLocalDeviceIdentity()
        assertEquals(device.deviceId, resolvedDevice.deviceId)
        assertEquals(device.signingKey, resolvedDevice.signingKey)
        assertNotNull(resolvedDevice.signedPreKey)
        assertEquals(device.signedPreKey!!.id, resolvedDevice.signedPreKey.id)

        val localSpk = resolver.getCurrentLocalSignedPreKey()
        assertEquals(resolvedDevice.signedPreKey.id, localSpk.id)
        assertEquals(resolvedDevice.signedPreKey.publicKey, localSpk.publicKey)
        // Responder paths (X3DH bootstrap) sign/decrypt with this key: the DB row
        // never carries private material, so the resolver must attach the keystore key.
        assertNotNull(localSpk.privateKey)
        val storedSpkPrivate = assertNotNull(store.getKey(KeySlot.SignedPreKeyPrivate(device.signedPreKey!!.id)))
        assertContentEquals(storedSpkPrivate, localSpk.privateKey.bytes)
        val direct = resolver.resolveLocalSignedPreKey(localSpk.id)
        assertEquals(localSpk.privateKey, direct.privateKey)

        assertEquals(fixedTor, repo.getDeclaredTorEndpoint(device.deviceId))
    }

    @Test
    fun resolver_throwsWhenDeviceDbRowMissing() = runTest {
        val (repo, _, triple) = stack()
        val (resolver, provisioning) = triple

        provisioning.createNewAccountIdentity(displayName = "Recovery User")
        provisioning.createNewDeviceIdentity()

        repo.clearLocalDeviceRecord()

        // Strict: missing rows are BootDiagnoser territory, never re-inserted here.
        assertFailsWith<CryptoException.MissingDeviceRecord> {
            resolver.getLocalDeviceIdentity()
        }
    }

    @Test
    fun resolver_throwsWhenDeviceDbRowMissingEvenWithPrivateKeysOnly() = runTest {
        val (repo, store, triple) = stack()
        val (resolver, provisioning) = triple

        provisioning.createNewAccountIdentity(displayName = "Private-only recovery")
        provisioning.createNewDeviceIdentity()

        store.deleteKey(KeySlot.DeviceSigningPublic)
        store.deleteKey(KeySlot.DeviceEncryptionPublic)
        repo.clearLocalDeviceRecord()

        assertFailsWith<CryptoException.MissingDeviceRecord> {
            resolver.getLocalDeviceIdentity()
        }
    }

    @Test
    fun resolver_throwsWhenAccountDbRowMissing() = runTest {
        val (repo, _, triple) = stack()
        val (resolver, provisioning) = triple

        val account = provisioning.createNewAccountIdentity(displayName = "Recovery User")
        repo.accounts.remove(account.accountId.id)
        repo.localAccount = null


        assertFailsWith<CryptoException.MissingAccountRecord> {
            resolver.getLocalAccountIdentity()
        }
    }

    @Test
    fun repository_insertPeerDevice_then_resolveTor_and_listPeers() = runTest {
        val (repo, _, triple) = stack()
        val (resolver, provisioning) = triple

        val account = provisioning.createNewAccountIdentity("Acc")
        val localDevice = provisioning.createNewDeviceIdentity()

        val remoteSigning = DefaultCryptoProvider().generateSigningKeyPair()
        val remoteEncryption = DefaultCryptoProvider().generateEncryptionKeyPair()
        val remotePeer =
            DeviceIdentity(
                deviceId = PeerId("peerdevidaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                signingKey = remoteSigning.publicKey,
                encryptionKey = remoteEncryption.publicKey,
            )
        val peerTor = TorEndpoint(onionAddress = "peerfixture.onion", port = 995)

        repo.insertPeerDevice(account.accountId, DeviceType.DESKTOP, remotePeer, peerTor)

        assertEquals(peerTor, repo.getDeclaredTorEndpoint(remotePeer.deviceId))

        val peers = repo.getAllPeerDevicesForAccount(account.accountId)
        assertTrue(peers.map { it.id }.toSet().contains(localDevice.deviceId.id))
        assertTrue(peers.map { it.id }.toSet().contains(remotePeer.deviceId.id))
    }

    @Test
    fun repository_insertPeerAccount_persistsInRepository() = runTest {
        val (repo, _, _) = stack()

        val signing = DefaultCryptoProvider().generateSigningKeyPair()
        val acc =
            AccountIdentity(
                accountId = AccountId("external-acc-id"),
                displayName = "Peer Account",
                signingKey = signing.publicKey,
            )
        repo.insertPeerAccount(
            identity = acc,
            role = AccountRole.MEMBER,
            status = IdentityStatus.ACTIVE,
            displayName = acc.displayName,
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
        assertEquals(original.signingKey!!, imported.signingKey!!)

        val resolved = targetResolver.getLocalAccountIdentity()
        assertEquals(original.accountId, resolved.accountId)
        assertEquals("Recover Me", resolved.displayName)
        assertTrue(targetResolver.localAccountSigningKey().bytes.isNotEmpty())
    }

    @Test
    fun provisioning_verifyRecoveryKey_acceptsOwnKey() = runTest {
        val (_, _, source) = stack()
        val (_, sourceProvisioning) = source

        sourceProvisioning.createNewAccountIdentity(displayName = "Verify Me")
        val recoveryKey = sourceProvisioning.exportLocalAccountRecoveryKey()

        assertTrue(sourceProvisioning.verifyRecoveryKey(recoveryKey))
    }

    @Test
    fun provisioning_verifyRecoveryKey_rejectsMalformedAndForeignKeys() = runTest {
        val (_, _, source) = stack()
        val (_, sourceProvisioning) = source
        sourceProvisioning.createNewAccountIdentity(displayName = "Verify Me")

        assertFalse(sourceProvisioning.verifyRecoveryKey("not-a-recovery-key"))

        val (_, _, other) = stack()
        val (_, otherProvisioning) = other
        otherProvisioning.createNewAccountIdentity(displayName = "Someone Else")
        val foreignKey = otherProvisioning.exportLocalAccountRecoveryKey()

        assertFalse(sourceProvisioning.verifyRecoveryKey(foreignKey))
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
        store.markOffered(opk.id)
        val consumed = store.consume(opk.id)
        assertNotNull(consumed)
        assertEquals(opk.publicKey, consumed.publicKey)
        assertEquals(null, store.consume(opk.id))
    }
}
