package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

/**
 * Tests [DefaultSignatureProvider] against [SignatureProvider]: signs with the local signing key
 * and verifies using the peer record exposed by [IdentityResolver].
 */
class DefaultSignatureProviderTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun signDetached_thenVerifyDetached_withRegisteredPeer_succeeds() {
        val signingKeys = crypto.generateSigningKeyPair()
        val peerId = crypto.peerIdFromPublicKey(signingKeys.publicKey)
        val encryptionKeys = crypto.generateEncryptionKeyPair()

        val peerRecord = DeviceIdentityRecord(
            deviceId = peerId,
            signing = IdentityPublicKeyRecord(
                keyId = "test-signing",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = signingKeys.publicKey,
            ),
            encryption = IdentityPublicKeyRecord(
                keyId = "test-encryption",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                publicKey = encryptionKeys.publicKey,
            ),
        )

        val resolver = FakeIdentityResolverForSignature(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(peerId to peerRecord),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, crypto)

        val message = "router-bound payload".encodeToByteArray()
        val sig = signatureProvider.signDetached(message)

        assertTrue(signatureProvider.verifyDetached(peerId, message, sig))
    }

    @Test
    fun verifyDetached_falseWhenMessageModified() {
        val signingKeys = crypto.generateSigningKeyPair()
        val peerId = crypto.peerIdFromPublicKey(signingKeys.publicKey)
        val encryptionKeys = crypto.generateEncryptionKeyPair()

        val peerRecord = DeviceIdentityRecord(
            deviceId = peerId,
            signing = IdentityPublicKeyRecord(
                keyId = "test-signing",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = signingKeys.publicKey,
            ),
            encryption = IdentityPublicKeyRecord(
                keyId = "test-encryption",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                publicKey = encryptionKeys.publicKey,
            ),
        )

        val resolver = FakeIdentityResolverForSignature(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(peerId to peerRecord),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, crypto)

        val message = "original".encodeToByteArray()
        val sig = signatureProvider.signDetached(message)
        val tampered = message + byteArrayOf(0)

        assertFalse(signatureProvider.verifyDetached(peerId, tampered, sig))
    }

    @Test
    fun verifyDetached_falseWhenSignatureTampered() {
        val signingKeys = crypto.generateSigningKeyPair()
        val peerId = crypto.peerIdFromPublicKey(signingKeys.publicKey)
        val encryptionKeys = crypto.generateEncryptionKeyPair()

        val peerRecord = DeviceIdentityRecord(
            deviceId = peerId,
            signing = IdentityPublicKeyRecord(
                keyId = "test-signing",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = signingKeys.publicKey,
            ),
            encryption = IdentityPublicKeyRecord(
                keyId = "test-encryption",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                publicKey = encryptionKeys.publicKey,
            ),
        )

        val resolver = FakeIdentityResolverForSignature(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(peerId to peerRecord),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, crypto)

        val message = "integrity".encodeToByteArray()
        val sig = signatureProvider.signDetached(message).copyOf()
        sig[0] = (sig[0].toInt() xor 0xff).toByte()

        assertFalse(signatureProvider.verifyDetached(peerId, message, sig))
    }

    @Test
    fun verifyDetached_falseWhenPeerUnknown() {
        val signingKeys = crypto.generateSigningKeyPair()
        val peerId = crypto.peerIdFromPublicKey(signingKeys.publicKey)
        val encryptionKeys = crypto.generateEncryptionKeyPair()

        val peerRecord = DeviceIdentityRecord(
            deviceId = peerId,
            signing = IdentityPublicKeyRecord(
                keyId = "test-signing",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = signingKeys.publicKey,
            ),
            encryption = IdentityPublicKeyRecord(
                keyId = "test-encryption",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                publicKey = encryptionKeys.publicKey,
            ),
        )

        val resolver = FakeIdentityResolverForSignature(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(peerId to peerRecord),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, crypto)

        val otherPeerId = crypto.peerIdFromPublicKey(crypto.generateSigningKeyPair().publicKey)
        val message = "m".encodeToByteArray()
        val sig = signatureProvider.signDetached(message)

        assertFalse(signatureProvider.verifyDetached(otherPeerId, message, sig))
    }

    /**
     * Only [loadLocalPrivateKey] and [resolvePeerIdentityRecord] are used by [DefaultSignatureProvider].
     */
    private class FakeIdentityResolverForSignature(
        private val localSigningPrivateKey: ByteArray,
        private val peerRecords: Map<PeerId, DeviceIdentityRecord>,
    ) : IdentityResolver {

        override fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = error("not used in test")

        override fun getLocalAccountIdentityRecord(): AccountIdentityRecord = error("not used in test")

        override fun loadLocalPrivateKey(purpose: IdentityKeyPurpose): ByteArray {
            require(purpose == IdentityKeyPurpose.SIGNING) { "unexpected purpose $purpose" }
            return localSigningPrivateKey
        }

        override fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? = peerRecords[deviceId]

        override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint = error("not used in test")

        override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used in test")

        override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used in test")
    }
}
