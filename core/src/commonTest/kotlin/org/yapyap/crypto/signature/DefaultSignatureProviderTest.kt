package org.yapyap.crypto.signature

import org.yapyap.routing.directory.DeviceRouteStore

import org.yapyap.routing.directory.DeviceDirectory

import org.yapyap.crypto.keys.*

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [org.yapyap.crypto.signature.DefaultSignatureProvider] against [org.yapyap.crypto.signature.SignatureProvider]: signs with the local signing key
 * and verifies using the peer record exposed by [org.yapyap.crypto.identity.IdentityResolver].
 */
class DefaultSignatureProviderTest {

    private val crypto = DefaultCryptoProvider()

    @Test
    fun signDetached_thenVerifyDetached_withRegisteredPeer_succeeds() = runTest {
        val signingKeys = crypto.generateSigningKeyPair()
        val peerId = crypto.peerIdFromPublicKey(signingKeys.publicKey)
        val encryptionKeys = crypto.generateEncryptionKeyPair()

        val peerRecord = DeviceIdentity(
            deviceId = peerId,
            signingKey = signingKeys.publicKey,
            encryptionKey = encryptionKeys.publicKey,
        )

        val resolver = FakeIdentityResolverForSignature(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(peerId to peerRecord),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, resolver, crypto)

        val message = "router-bound payload".encodeToByteArray()
        val sig = signatureProvider.sign(message)

        assertTrue(signatureProvider.verify(peerId, message, sig))
    }

    @Test
    fun verifyDetached_falseWhenMessageModified() = runTest {
        val signingKeys = crypto.generateSigningKeyPair()
        val peerId = crypto.peerIdFromPublicKey(signingKeys.publicKey)
        val encryptionKeys = crypto.generateEncryptionKeyPair()

        val peerRecord = DeviceIdentity(
            deviceId = peerId,
            signingKey = signingKeys.publicKey,
            encryptionKey = encryptionKeys.publicKey,
        )

        val resolver = FakeIdentityResolverForSignature(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(peerId to peerRecord),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, resolver, crypto)

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

        val peerRecord = DeviceIdentity(
            deviceId = peerId,
            signingKey = signingKeys.publicKey,
            encryptionKey = encryptionKeys.publicKey,
        )

        val resolver = FakeIdentityResolverForSignature(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(peerId to peerRecord),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, resolver, crypto)

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

        val peerRecord = DeviceIdentity(
            deviceId = peerId,
            signingKey = signingKeys.publicKey,
            encryptionKey = encryptionKeys.publicKey,
        )

        val resolver = FakeIdentityResolverForSignature(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(peerId to peerRecord),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, resolver, crypto)

        val otherPeerId = crypto.peerIdFromPublicKey(crypto.generateSigningKeyPair().publicKey)
        val message = "m".encodeToByteArray()
        val sig = signatureProvider.sign(message)

        assertFailsWith<CryptoException.MissingDeviceRecord> { signatureProvider.verify(otherPeerId, message, sig) }
    }

    /**
     * Only [getLocalDevicePrivateKey] and [resolvePeerIdentity] are used by [DefaultSignatureProvider].
     */
    private class FakeIdentityResolverForSignature(
        private val localSigningPrivateKey: SigningPrivateKey,
        private val peerRecords: Map<PeerId, DeviceIdentity>,
    ) : LocalIdentity, LocalKeys, PeerKeys, DeviceDirectory, DeviceRouteStore {

        override suspend fun getLocalDeviceIdentity(): DeviceIdentity = error("not used in test")

        override suspend fun getLocalAccountIdentity(): AccountIdentity = error("not used in test")
        override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? = IdentityStatus.ACTIVE
        override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? = error("not used in test")
        suspend fun isLocalAccountAdmin(): Boolean = error("not used in test")
        suspend fun isLocalAccountOwner(): Boolean = error("not used in test")

        override suspend fun localDeviceSigningKey(): SigningPrivateKey = localSigningPrivateKey

        override suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey = error("not used")

        override suspend fun localAccountSigningKey(): SigningPrivateKey = error("not used in test")

        override suspend fun getLocalDeviceId(): PeerId = error("not used in test")
        override suspend fun getLocalAccountId(): AccountId = error("not used in test")

        override suspend fun resolvePeerIdentity(deviceId: PeerId): DeviceIdentity =
            peerRecords[deviceId] ?: throw CryptoException.MissingDeviceRecord(deviceId.id)

        override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? = error("not used in test")

        override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used in test")

        suspend fun getAllPeers(): List<PeerId> = error("not used in test")

        override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = error("not used in test")

        override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) =
            error("not used in test")

        override suspend fun resolvePeerPreKeyBundle(
            deviceId: PeerId,
            signedPreKeyId: SignedPreKeyId?
        ): PeerPreKeyBundle = error("not used in test")

        override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey = error("not used in test")

        override suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey =
            error("not used in test")

        override suspend fun getAllActiveDeviceIds(): List<PeerId> =
            getAllPeers().filter { getDeviceStatus(it) != IdentityStatus.BANNED }

        override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
            accountIds.flatMap { getAllPeerDevicesForAccount(it) }


        override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? =
            resolveTorEndpointForDevice(deviceId)
    }
}
