package org.yapyap.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DefaultIdentityProvisioning
import org.yapyap.crypto.identity.DefaultIdentityResolver
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityKeyServiceConfig
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.RecordingAppLogger
import org.yapyap.persistence.key.KeyReference
import org.yapyap.persistence.key.KeyType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.time.FixedEpochSecondsProvider

class DefaultIdentityOrchestrationTest {

    private val fixedTor = TorEndpoint(onionAddress = "fixture-identity.onion", port = 443)
    private val config =
        IdentityKeyServiceConfig(
            defaultOnionAddress = "fixture-identity.onion",
            defaultOnionPort = 443L,
        )

    private fun stack(logger: RecordingAppLogger = RecordingAppLogger()): Triple<
        InMemoryIdentityKeyRepository,
        InMemoryKeyStore,
        Triple<DefaultIdentityResolver, DefaultIdentityProvisioning, RecordingAppLogger>,
        > {
        val repo = InMemoryIdentityKeyRepository(defaultLocalTor = fixedTor)
        val store = InMemoryKeyStore()
        val crypto = KmpCryptoProvider()
        val resolver = DefaultIdentityResolver(crypto, repo, store, config, logger)
        val timeProvider = FixedEpochSecondsProvider(0L)
        val provisioning = DefaultIdentityProvisioning(crypto, repo, store, config, resolver, timeProvider, logger)
        return Triple(repo, store, Triple(resolver, provisioning, logger))
    }

    @Test
    fun provisioning_createAccount_then_createDevice_resolver_roundTrip() = runTest {
        val (_, _, triple) = stack()
        val (resolver, provisioning, logger) = triple

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

        assertTrue(
            logger.entries.any {
                it.component == LogComponent.CRYPTO &&
                    it.event == LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED
            },
        )
        assertTrue(
            logger.entries.any {
                it.component == LogComponent.CRYPTO &&
                    it.event == LogEvent.IDENTITY_DEVICE_RECORD_CREATED
            },
        )
    }

    @Test
    fun resolver_recoversDeviceRecordFromKeystoreWhenDbRowMissing() = runTest {
        val (repo, store, triple) = stack()
        val (resolver, provisioning, logger) = triple

        provisioning.createNewAccountIdentity(displayName = "Recovery User")
        val device = provisioning.createNewDeviceIdentity()

        repo.clearLocalDeviceRecord()

        val recovered = resolver.getLocalDeviceIdentityRecord()
        assertEquals(device.deviceId, recovered.deviceId)
        assertContentEquals(device.signing.publicKey, recovered.signing.publicKey)
        assertContentEquals(device.encryption.publicKey, recovered.encryption.publicKey)
        assertContentEquals(device.keySignature, recovered.keySignature)
        assertNotNull(resolver.resolvePeerIdentityRecord(device.deviceId))

        assertTrue(
            logger.entries.any {
                it.component == LogComponent.CRYPTO &&
                    it.event == LogEvent.IDENTITY_DEVICE_RECORD_MISSING
            },
        )
        assertTrue(
            logger.entries.any {
                it.component == LogComponent.CRYPTO &&
                    it.event == LogEvent.IDENTITY_DEVICE_RECORD_CREATED
            },
        )
    }

    @Test
    fun resolver_recoversDeviceRecordFromPrivateKeysOnlyWhenPublicKeysMissing() = runTest {
        val (repo, store, triple) = stack()
        val (resolver, provisioning, _) = triple

        provisioning.createNewAccountIdentity(displayName = "Private-only recovery")
        val device = provisioning.createNewDeviceIdentity()

        val signingKeyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.SIGNING.name.lowercase()
        val encryptionKeyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.ENCRYPTION.name.lowercase()
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
    fun resolver_getLocalAccount_throwsWhenAccountRowMissing() = runTest {
        val (_, store, triple) = stack()
        val (resolver, _, _) = triple

        val crypto = KmpCryptoProvider()
        val kp = crypto.generateSigningKeyPair()
        val keyId = config.defaultAccountLocalKeyPrefix + IdentityKeyPurpose.SIGNING.name.lowercase()
        store.putKey(
            KeyReference(keyId, IdentityKeyPurpose.SIGNING, KeyType.PRIVATE),
            kp.privateKey,
        )
        store.putKey(
            KeyReference(keyId, IdentityKeyPurpose.SIGNING, KeyType.PUBLIC),
            kp.publicKey,
        )

        assertFailsWith<IllegalStateException> {
            resolver.getLocalAccountIdentityRecord()
        }
    }

    @Test
    fun provisioning_provisionPeerDevice_then_resolveTor_and_listPeers() = runTest {
        val (repo, _, triple) = stack()
        val (resolver, provisioning, _) = triple

        val account = provisioning.createNewAccountIdentity("Acc")
        val localDevice = provisioning.createNewDeviceIdentity()

        val remoteSigning = KmpCryptoProvider().generateSigningKeyPair()
        val remoteEncryption = KmpCryptoProvider().generateEncryptionKeyPair()
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

        provisioning.provisionDeviceIdentity(account.accountId, remotePeer, peerTor)

        assertEquals(peerTor, resolver.resolveTorEndpointForDevice(remotePeer.deviceId))

        val peers = resolver.getAllPeerDevicesForAccount(account.accountId)
        assertTrue(peers.map { it.id }.toSet().contains(localDevice.deviceId.id))
        assertTrue(peers.map { it.id }.toSet().contains(remotePeer.deviceId.id))
    }

    @Test
    fun provisioning_provisionPeerAccount_persistsInRepository() = runTest {
        val (repo, _, triple) = stack()
        val (_, provisioning, _) = triple

        val signing = KmpCryptoProvider().generateSigningKeyPair()
        val acc =
            AccountIdentityRecord(
                accountId = AccountId("external-acc-id"),
                key = IdentityPublicKeyRecord("ext", 1L, IdentityKeyPurpose.SIGNING, signing.publicKey),
            )
        provisioning.provisionAccountIdentity(
            displayName = "Peer Account",
            accountIdentity = acc,
            admin = false,
            status = AccountStatus.ACTIVE,
        )

        assertNotNull(repo.accounts["external-acc-id"])
        assertEquals(acc.accountId, repo.accounts["external-acc-id"]!!.accountId)
    }

    @Test
    fun oneTimePreKeyStore_allocateThenConsume_onceOnly() = runTest {
        val crypto = KmpCryptoProvider()
        val store = InMemoryOpkRepository(crypto)

        val opk = store.allocate()
        store.markOffered(opk.keyId)
        val consumed = store.consume(opk.keyId)
        assertNotNull(consumed)
        assertContentEquals(opk.publicKey, consumed.publicKey)
        assertEquals(null, store.consume(opk.keyId))
    }
}
