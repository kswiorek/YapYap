package org.yapyap.crypto.e2ee

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.session.DoubleRatchetSession
import org.yapyap.crypto.e2ee.session.X3dhHandshake
import org.yapyap.crypto.e2ee.session.X3dhMode
import org.yapyap.crypto.identity.LocalOneTimePreKey
import org.yapyap.crypto.identity.LocalSignedPreKey
import org.yapyap.crypto.identity.PeerPreKeyBundle
import org.yapyap.crypto.keys.EncryptionKeyPair
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.identity.OneTimePreKeyId
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId
import kotlin.test.*

class X3dhHandshakeTest {

    private val crypto = DefaultCryptoProvider()
    private val x3dh = X3dhHandshake(crypto)

    @Test
    fun initiatorAndResponder_compute3Dh_agreeOnSharedSecret() = runTest {
        val fixture = fixtureKeys()
        val ekA = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute3Dh(fixture.aliceIdentity.privateKey, fixture.bobRemote, ekA)
        val responder = x3dh.responderCompute3Dh(
            localIdentityKey = fixture.bobIdentity.privateKey,
            localSignedPreKey = fixture.bobSignedPreKey,
            remoteIdentityKey = fixture.aliceIdentity.publicKey,
            wire = initiator.wire,
        )
        assertContentEquals(initiator.sharedSecret, responder.sharedSecret)
        assertEquals(X3dhMode.THREE_DH, initiator.wire.mode)
    }

    @Test
    fun initiatorAndResponder_compute4Dh_agreeOnSharedSecret() = runTest {
        val fixture = fixtureKeys()
        val ekA = crypto.generateEncryptionKeyPair()
        val opk = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute4Dh(
            localIdentityKey = fixture.aliceIdentity.privateKey,
            remote = fixture.bobRemote,
            ephemeral = ekA,
            oneTimePreKeyPublicKey = opk.publicKey,
            oneTimePreKeyId = "opk-1",
        )
        val responder = x3dh.responderCompute4Dh(
            localIdentityKey = fixture.bobIdentity.privateKey,
            localSignedPreKey = fixture.bobSignedPreKey,
            oneTimePreKey = LocalOneTimePreKey(OneTimePreKeyId("opk-1"), opk.publicKey, opk.privateKey),
            remoteIdentityKey = fixture.aliceIdentity.publicKey,
            wire = initiator.wire,
        )
        assertContentEquals(initiator.sharedSecret, responder.sharedSecret)
        assertEquals(X3dhMode.FOUR_DH, initiator.wire.mode)
    }

    @Test
    fun compute4Dh_differsFrom3Dh_withSameEphemeral() = runTest {
        val fixture = fixtureKeys()
        val ekA = crypto.generateEncryptionKeyPair()
        val opk = crypto.generateEncryptionKeyPair()
        val threeDh = x3dh.initiatorCompute3Dh(fixture.aliceIdentity.privateKey, fixture.bobRemote, ekA)
        val fourDh = x3dh.initiatorCompute4Dh(
            localIdentityKey = fixture.aliceIdentity.privateKey,
            remote = fixture.bobRemote,
            ephemeral = ekA,
            oneTimePreKeyPublicKey = opk.publicKey,
            oneTimePreKeyId = "opk-1",
        )
        assertNotEquals(threeDh.sharedSecret.contentHashCode(), fourDh.sharedSecret.contentHashCode())
    }

    @Test
    fun initiatorBootstrap_usesEphemeralAsLocalRatchetKey() = runTest {
        val fixture = fixtureKeys()
        val ekA = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute3Dh(fixture.aliceIdentity.privateKey, fixture.bobRemote, ekA)
        assertContentEquals(ekA.privateKey.bytes, initiator.ratchetBootstrap.localDhPrivateKey)
        assertContentEquals(ekA.publicKey.bytes, initiator.ratchetBootstrap.localDhPublicKey)
        assertContentEquals(initiator.wire.ephemeralPublicKey, initiator.ratchetBootstrap.localDhPublicKey)
    }

    @Test
    fun compute3Dh_ratchetRoundTrip() = runTest {
        val fixture = fixtureKeys()
        val ekA = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute3Dh(fixture.aliceIdentity.privateKey, fixture.bobRemote, ekA)
        val responder = x3dh.responderCompute3Dh(
            localIdentityKey = fixture.bobIdentity.privateKey,
            localSignedPreKey = fixture.bobSignedPreKey,
            remoteIdentityKey = fixture.aliceIdentity.publicKey,
            wire = initiator.wire,
        )
        val alice = DoubleRatchetSession.createInitiator(crypto, initiator.ratchetBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, responder.ratchetBootstrap)

        val plaintext = "x3dh epoch 1".encodeToByteArray()
        assertContentEquals(plaintext, bob.decrypt(alice.encrypt(plaintext)))
    }

    @Test
    fun compute4Dh_ratchetRoundTrip() = runTest {
        val fixture = fixtureKeys()
        val ekA = crypto.generateEncryptionKeyPair()
        val opk = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute4Dh(
            localIdentityKey = fixture.aliceIdentity.privateKey,
            remote = fixture.bobRemote,
            ephemeral = ekA,
            oneTimePreKeyPublicKey = opk.publicKey,
            oneTimePreKeyId = "opk-1",
        )
        val responder = x3dh.responderCompute4Dh(
            localIdentityKey = fixture.bobIdentity.privateKey,
            localSignedPreKey = fixture.bobSignedPreKey,
            oneTimePreKey = LocalOneTimePreKey(OneTimePreKeyId("opk-1"), opk.publicKey, opk.privateKey),
            remoteIdentityKey = fixture.aliceIdentity.publicKey,
            wire = initiator.wire,
        )
        val alice = DoubleRatchetSession.createInitiator(crypto, initiator.ratchetBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, responder.ratchetBootstrap)

        val plaintext = "x3dh epoch 2".encodeToByteArray()
        assertContentEquals(plaintext, bob.decrypt(alice.encrypt(plaintext)))
    }

    @Test
    fun responderCompute3Dh_rejectsSignedPreKeyIdMismatch() = runTest {
        val fixture = fixtureKeys()
        val initiator = x3dh.initiatorCompute3Dh(
            fixture.aliceIdentity.privateKey,
            fixture.bobRemote,
            crypto.generateEncryptionKeyPair(),
        )
        val badWire = initiator.wire.copy(signedPreKeyId = "wrong-spk")
        assertFailsWith<CryptoSessionException.HandshakeMismatch> {
            x3dh.responderCompute3Dh(
                localIdentityKey = fixture.bobIdentity.privateKey,
                localSignedPreKey = fixture.bobSignedPreKey,
                remoteIdentityKey = fixture.aliceIdentity.publicKey,
                wire = badWire,
            )
        }
    }

    @Test
    fun responderCompute4Dh_rejectsOneTimePreKeyIdMismatch() = runTest {
        val fixture = fixtureKeys()
        val opk = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute4Dh(
            localIdentityKey = fixture.aliceIdentity.privateKey,
            remote = fixture.bobRemote,
            ephemeral = crypto.generateEncryptionKeyPair(),
            oneTimePreKeyPublicKey = opk.publicKey,
            oneTimePreKeyId = "opk-1",
        )
        assertFailsWith<CryptoSessionException.HandshakeMismatch> {
            x3dh.responderCompute4Dh(
                localIdentityKey = fixture.bobIdentity.privateKey,
                localSignedPreKey = fixture.bobSignedPreKey,
                oneTimePreKey = LocalOneTimePreKey(OneTimePreKeyId("opk-2"), opk.publicKey, opk.privateKey),
                remoteIdentityKey = fixture.aliceIdentity.publicKey,
                wire = initiator.wire,
            )
        }
    }

    private suspend fun fixtureKeys(): FixtureKeys {
        val aliceIk = crypto.generateEncryptionKeyPair()
        val bobSigning = crypto.generateSigningKeyPair()
        val bobIk = crypto.generateEncryptionKeyPair()
        val bobSpk = crypto.generateEncryptionKeyPair()
        val bobSignedPreKey = SignedPreKey(
            id = SignedPreKeyId("spk-1"),
            deviceId = crypto.peerIdFromPublicKey(bobSigning.publicKey),
            publicKey = bobSpk.publicKey,
            signature = crypto.signDetached(bobSigning.privateKey, bobSpk.publicKey.bytes),
        )
        return FixtureKeys(
            aliceIdentity = aliceIk,
            bobRemote = PeerPreKeyBundle(identityKey = bobIk.publicKey, signedPreKey = bobSignedPreKey),
            bobIdentity = bobIk,
            bobSignedPreKey = LocalSignedPreKey(bobSignedPreKey, bobSpk.privateKey),
        )
    }

    private data class FixtureKeys(
        val aliceIdentity: EncryptionKeyPair,
        val bobRemote: PeerPreKeyBundle,
        val bobIdentity: EncryptionKeyPair,
        val bobSignedPreKey: LocalSignedPreKey,
    )
}
