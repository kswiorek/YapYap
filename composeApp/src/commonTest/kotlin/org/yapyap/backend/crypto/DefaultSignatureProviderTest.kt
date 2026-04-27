package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.DeviceAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultSignatureProviderTest {
    private val crypto = KmpCryptoProvider()

    @Test
    fun signsAndVerifiesDetachedPayload() {
        val alice = DeviceAddress(accountId = "alice", deviceId = "alice-device")
        val bob = DeviceAddress(accountId = "bob", deviceId = "bob-device")

        val repository = SignatureTestIdentityPublicKeyRepository()
        val keyStore = SignatureTestPrivateKeyStore()
        val aliceService = DefaultIdentityResolver(
            localAddress = alice,
            cryptoProvider = crypto,
            publicKeyRepository = repository,
            privateKeyStore = keyStore,
        )
        val bobService = DefaultIdentityResolver(
            localAddress = bob,
            cryptoProvider = crypto,
            publicKeyRepository = repository,
            privateKeyStore = keyStore,
        )
        val aliceSignatureProvider = DefaultSignatureProvider(
            localAddress = alice,
            identityResolver = aliceService,
            cryptoProvider = crypto,
        )
        val bobSignatureProvider = DefaultSignatureProvider(
            localAddress = bob,
            identityResolver = bobService,
            cryptoProvider = crypto,
        )

        aliceService.getOrCreateLocalIdentity(alice)
        bobService.getOrCreateLocalIdentity(bob)

        val payload = "signed-message".encodeToByteArray()
        val keyId = aliceSignatureProvider.resolveLocalSigningKeyId()
        val signature = aliceSignatureProvider.signDetached(keyId, payload)

        assertTrue(bobSignatureProvider.verifyDetached(alice, payload, signature))
        assertFalse(bobSignatureProvider.verifyDetached(alice, "tampered".encodeToByteArray(), signature))
    }

    @Test
    fun verifyReturnsFalseWhenPeerKeyMissing() {
        val alice = DeviceAddress(accountId = "alice", deviceId = "alice-device")
        val bob = DeviceAddress(accountId = "bob", deviceId = "bob-device")

        val bobRepository = SignatureTestIdentityPublicKeyRepository()
        val bobService = DefaultIdentityResolver(
            localAddress = bob,
            cryptoProvider = crypto,
            publicKeyRepository = bobRepository,
            privateKeyStore = SignatureTestPrivateKeyStore(),
        )
        val bobSignatureProvider = DefaultSignatureProvider(
            localAddress = bob,
            identityResolver = bobService,
            cryptoProvider = crypto,
        )

        val payload = "signed-message".encodeToByteArray()
        val signature = byteArrayOf(1, 2, 3, 4)

        assertFalse(bobSignatureProvider.verifyDetached(alice, payload, signature))
    }
}

internal class SignatureTestIdentityPublicKeyRepository : IdentityPublicKeyRepository {
    private val keyByDeviceAndPurpose = mutableMapOf<Pair<String, IdentityKeyPurpose>, IdentityPublicKeyRecord>()

    override fun ensureAccountExists(accountId: String) = Unit

    override fun ensureDeviceExists(address: DeviceAddress) = Unit

    override fun upsertLocalIdentity(identity: DeviceIdentityRecord) {
        keyByDeviceAndPurpose[identity.address.deviceId to IdentityKeyPurpose.SIGNING] = identity.signing
        keyByDeviceAndPurpose[identity.address.deviceId to IdentityKeyPurpose.ENCRYPTION] = identity.encryption
    }

    override fun resolveDeviceKey(deviceId: String, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord? {
        return keyByDeviceAndPurpose[deviceId to purpose]
    }
}

internal class SignatureTestPrivateKeyStore : PrivateKeyStore {
    private val keys = mutableMapOf<KeyReference, ByteArray>()

    override fun putKey(ref: KeyReference, key: ByteArray) {
        keys[ref] = key.copyOf()
    }

    override fun getKey(ref: KeyReference): ByteArray? = keys[ref]?.copyOf()
}
