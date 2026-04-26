package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.DeviceAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DefaultIdentityKeyServiceTest {
    private val crypto = KmpCryptoProvider()

    @Test
    fun createsIdentityAndReusesExistingKeys() {
        val address = DeviceAddress(accountId = "alice", deviceId = "alice-device")
        val repository = InMemoryIdentityPublicKeyRepository()
        val privateKeyStore = InMemoryPrivateKeyStore()
        val service = DefaultIdentityKeyService(
            localAddress = address,
            cryptoProvider = crypto,
            publicKeyRepository = repository,
            privateKeyStore = privateKeyStore,
        )

        val created = service.getOrCreateLocalIdentity(address)
        val reused = service.getOrCreateLocalIdentity(address)

        assertEquals(created.signing.keyId, reused.signing.keyId)
        assertEquals(created.encryption.keyId, reused.encryption.keyId)
        assertEquals(1, repository.upsertCount)
        assertContentEquals(
            created.signing.publicKey,
            service.resolvePeerPublicKey(address, IdentityKeyPurpose.SIGNING),
        )
    }

    @Test
    fun failsWhenDbReferencesMissingPrivateKey() {
        val address = DeviceAddress(accountId = "alice", deviceId = "alice-device")
        val repository = InMemoryIdentityPublicKeyRepository()
        val privateKeyStore = InMemoryPrivateKeyStore()
        val service = DefaultIdentityKeyService(
            localAddress = address,
            cryptoProvider = crypto,
            publicKeyRepository = repository,
            privateKeyStore = privateKeyStore,
        )

        val signing = IdentityPublicKeyRecord(
            keyId = "signing-v1-deadbeefdeadbeef",
            keyVersion = 1,
            purpose = IdentityKeyPurpose.SIGNING,
            publicKey = byteArrayOf(1, 2, 3),
        )
        val encryption = IdentityPublicKeyRecord(
            keyId = "encryption-v1-deadbeefdeadbeef",
            keyVersion = 1,
            purpose = IdentityKeyPurpose.ENCRYPTION,
            publicKey = byteArrayOf(4, 5, 6),
        )
        repository.upsertLocalIdentity(LocalIdentityRecord(address, signing, encryption))

        assertFailsWith<IllegalStateException> {
            service.getOrCreateLocalIdentity(address)
        }
    }
}

private class InMemoryIdentityPublicKeyRepository : IdentityPublicKeyRepository {
    private val keyByDeviceAndPurpose = mutableMapOf<Pair<String, IdentityKeyPurpose>, IdentityPublicKeyRecord>()
    private val accounts = mutableSetOf<String>()
    private val devices = mutableSetOf<String>()
    var upsertCount: Int = 0

    override fun ensureAccountExists(accountId: String) {
        accounts += accountId
    }

    override fun ensureDeviceExists(address: DeviceAddress) {
        ensureAccountExists(address.accountId)
        devices += address.deviceId
    }

    override fun upsertLocalIdentity(identity: LocalIdentityRecord) {
        upsertCount += 1
        ensureDeviceExists(identity.address)
        keyByDeviceAndPurpose[identity.address.deviceId to IdentityKeyPurpose.SIGNING] = identity.signing
        keyByDeviceAndPurpose[identity.address.deviceId to IdentityKeyPurpose.ENCRYPTION] = identity.encryption
    }

    override fun resolveDeviceKey(deviceId: String, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord? {
        return keyByDeviceAndPurpose[deviceId to purpose]
    }
}

private class InMemoryPrivateKeyStore : PrivateKeyStore {
    private val keys = mutableMapOf<PrivateKeyRef, ByteArray>()

    override fun putPrivateKey(ref: PrivateKeyRef, privateKey: ByteArray) {
        keys[ref] = privateKey.copyOf()
    }

    override fun getPrivateKey(ref: PrivateKeyRef): ByteArray? = keys[ref]?.copyOf()
}
