package org.yapyap.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.X3dhRemotePeerKeys
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.identity.SignedPreKeyRecord
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import kotlin.test.assertFailsWith

/**
 * Tests [org.yapyap.crypto.signature.DefaultSignatureProvider] against [org.yapyap.crypto.signature.SignatureProvider]: signs with the local signing key
 * and verifies using the peer record exposed by [org.yapyap.crypto.identity.IdentityResolver].
 */
class DefaultSignatureProviderTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun signDetached_thenVerifyDetached_withRegisteredPeer_succeeds() = runTest {
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
        val sig = signatureProvider.sign(message)

        assertTrue(signatureProvider.verify(peerId, message, sig))
    }

    @Test
    fun verifyDetached_falseWhenMessageModified() = runTest {
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
        val sig = signatureProvider.sign(message)
        val tampered = message + byteArrayOf(0)

        assertFalse(signatureProvider.verify(peerId, tampered, sig))
    }

    @Test
    fun verifyDetached_falseWhenSignatureTampered() = runTest {
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
        val sig = signatureProvider.sign(message).copyOf()
        sig[0] = (sig[0].toInt() xor 0xff).toByte()

        assertFalse(signatureProvider.verify(peerId, message, sig))
    }

    @Test
    fun verifyDetached_throwsWhenPeerUnknown() = runTest {
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
        val sig = signatureProvider.sign(message)

        assertFailsWith<CryptoException.MissingPeerRecord>{signatureProvider.verify(otherPeerId, message, sig)}
    }

    /**
     * Only [getLocalDevicePrivateKey] and [resolvePeerIdentityRecord] are used by [DefaultSignatureProvider].
     */
    private class FakeIdentityResolverForSignature(
        private val localSigningPrivateKey: ByteArray,
        private val peerRecords: Map<PeerId, DeviceIdentityRecord>,
    ) : IdentityResolver {

        override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = error("not used in test")

        override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord = error("not used in test")

        override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray {
            require(purpose == IdentityKeyPurpose.SIGNING) { "unexpected purpose $purpose" }
            return localSigningPrivateKey
        }
        override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used in test")
        override suspend fun getLocalDeviceId(): PeerId  = error("not used in test")

        override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? = peerRecords[deviceId]

        override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint = error("not used in test")

        override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used in test")

        override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used in test")

        override suspend fun resolvePeerX3dhRemoteKeys(
            deviceId: PeerId,
            signedPreKeyId: String?,
        ): X3dhRemotePeerKeys = error("not used in test")

        override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord = error("not used in test")

        override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord = error("not used in test")
    }
}
