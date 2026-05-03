package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.yapyap.backend.db.AccountStatus
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.RecordingAppLogger
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

class DefaultIdentityOrchestrationTest {

    private val fixedTor = TorEndpoint(onionAddress = "fixture-identity.onion", port = 443)
    private val config =
        IdentityKeyServiceConfig(
            defaultOnionAddress = "fixture-identity.onion",
            defaultOnionPort = 443L,
        )

    private fun stack(logger: RecordingAppLogger = RecordingAppLogger()): Triple<
        InMemoryIdentityPublicKeyRepository,
        InMemoryPrivateKeyStore,
        Triple<DefaultIdentityResolver, DefaultIdentityProvisioning, RecordingAppLogger>,
        > {
        val repo = InMemoryIdentityPublicKeyRepository(defaultLocalTor = fixedTor)
        val store = InMemoryPrivateKeyStore()
        val crypto = KmpCryptoProvider()
        val resolver = DefaultIdentityResolver(crypto, repo, store, config, logger)
        val provisioning = DefaultIdentityProvisioning(crypto, repo, store, config, resolver, logger)
        return Triple(repo, store, Triple(resolver, provisioning, logger))
    }

    @Test
    fun provisioning_createAccount_then_createDevice_resolver_roundTrip() {
        val (_, _, triple) = stack()
        val (resolver, provisioning, logger) = triple

        val account = provisioning.createNewAccountIdentity(displayName = "Local User")
        val device = provisioning.createNewDeviceIdentity()

        val resolvedAccount = resolver.getLocalAccountIdentityRecord()
        assertEquals(account.accountId, resolvedAccount.accountId)

        val resolvedDevice = resolver.getLocalDeviceIdentityRecord()
        assertEquals(device.deviceId, resolvedDevice.deviceId)
        assertEquals(device.signing.publicKey.contentHashCode(), resolvedDevice.signing.publicKey.contentHashCode())

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
    fun resolver_getLocalAccount_throwsWhenAccountRowMissing() {
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
    fun provisioning_provisionPeerDevice_then_resolveTor_and_listPeers() {
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
                encryption = IdentityPublicKeyRecord("rk-e", 0L, IdentityKeyPurpose.ENCRYPTION, remoteEncryption.publicKey),
            )
        val peerTor = TorEndpoint(onionAddress = "peerfixture.onion", port = 995)

        provisioning.provisionDeviceIdentity(account.accountId, remotePeer, peerTor)

        assertEquals(peerTor, resolver.resolveTorEndpointForDevice(remotePeer.deviceId))

        val peers = resolver.getAllPeerDevicesForAccount(account.accountId)
        assertTrue(peers.map { it.id }.toSet().contains(localDevice.deviceId.id))
        assertTrue(peers.map { it.id }.toSet().contains(remotePeer.deviceId.id))
    }

    @Test
    fun provisioning_provisionPeerAccount_persistsInRepository() {
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
}
