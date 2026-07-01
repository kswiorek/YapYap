package org.yapyap.crypto.e2ee

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.KmpCryptoProvider
import kotlin.test.*

class X3dhHandshakeTest {

    private val crypto = KmpCryptoProvider()
    private val x3dh = X3dhHandshake(crypto)

    @Test
    fun initiatorAndResponder_compute3Dh_agreeOnSharedSecret() = runTest {
        val fixture = fixtureKeys()
        val ekA = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute3Dh(fixture.aliceLocal, fixture.bobRemote, ekA)
        val responder = x3dh.responderCompute3Dh(
            local = fixture.bobLocal,
            remoteIdentityEncryptionPublicKey = fixture.aliceLocal.identityEncryptionPublicKey,
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
            local = fixture.aliceLocal,
            remote = fixture.bobRemote,
            ephemeral = ekA,
            oneTimePreKeyPublicKey = opk.publicKey,
            oneTimePreKeyId = "opk-1",
        )
        val responder = x3dh.responderCompute4Dh(
            local = fixture.bobLocal,
            oneTimePreKeyPrivateKey = opk.privateKey,
            oneTimePreKeyId = "opk-1",
            remoteIdentityEncryptionPublicKey = fixture.aliceLocal.identityEncryptionPublicKey,
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
        val threeDh = x3dh.initiatorCompute3Dh(fixture.aliceLocal, fixture.bobRemote, ekA)
        val fourDh = x3dh.initiatorCompute4Dh(
            local = fixture.aliceLocal,
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
        val initiator = x3dh.initiatorCompute3Dh(fixture.aliceLocal, fixture.bobRemote, ekA)
        assertContentEquals(ekA.privateKey, initiator.ratchetBootstrap.localDhPrivateKey)
        assertContentEquals(ekA.publicKey, initiator.ratchetBootstrap.localDhPublicKey)
        assertContentEquals(initiator.wire.ephemeralPublicKey, initiator.ratchetBootstrap.localDhPublicKey)
    }

    @Test
    fun compute3Dh_ratchetRoundTrip() = runTest {
        val fixture = fixtureKeys()
        val ekA = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute3Dh(fixture.aliceLocal, fixture.bobRemote, ekA)
        val responder = x3dh.responderCompute3Dh(
            local = fixture.bobLocal,
            remoteIdentityEncryptionPublicKey = fixture.aliceLocal.identityEncryptionPublicKey,
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
            local = fixture.aliceLocal,
            remote = fixture.bobRemote,
            ephemeral = ekA,
            oneTimePreKeyPublicKey = opk.publicKey,
            oneTimePreKeyId = "opk-1",
        )
        val responder = x3dh.responderCompute4Dh(
            local = fixture.bobLocal,
            oneTimePreKeyPrivateKey = opk.privateKey,
            oneTimePreKeyId = "opk-1",
            remoteIdentityEncryptionPublicKey = fixture.aliceLocal.identityEncryptionPublicKey,
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
            fixture.aliceLocal,
            fixture.bobRemote,
            crypto.generateEncryptionKeyPair(),
        )
        val badWire = initiator.wire.copy(signedPreKeyId = "wrong-spk")
        assertFailsWith<CryptoSessionException.HandshakeMismatch> {
            x3dh.responderCompute3Dh(
                local = fixture.bobLocal,
                remoteIdentityEncryptionPublicKey = fixture.aliceLocal.identityEncryptionPublicKey,
                wire = badWire,
            )
        }
    }

    @Test
    fun responderCompute4Dh_rejectsOneTimePreKeyIdMismatch() = runTest {
        val fixture = fixtureKeys()
        val opk = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute4Dh(
            local = fixture.aliceLocal,
            remote = fixture.bobRemote,
            ephemeral = crypto.generateEncryptionKeyPair(),
            oneTimePreKeyPublicKey = opk.publicKey,
            oneTimePreKeyId = "opk-1",
        )
        assertFailsWith<CryptoSessionException.HandshakeMismatch> {
            x3dh.responderCompute4Dh(
                local = fixture.bobLocal,
                oneTimePreKeyPrivateKey = opk.privateKey,
                oneTimePreKeyId = "opk-2",
                remoteIdentityEncryptionPublicKey = fixture.aliceLocal.identityEncryptionPublicKey,
                wire = initiator.wire,
            )
        }
    }

    private suspend fun fixtureKeys(): FixtureKeys {
        val aliceIk = crypto.generateEncryptionKeyPair()
        val bobIk = crypto.generateEncryptionKeyPair()
        val bobSpk = crypto.generateEncryptionKeyPair()
        return FixtureKeys(
            aliceLocal = X3dhLocalInitiatorKeys(
                identityEncryptionPrivateKey = aliceIk.privateKey,
                identityEncryptionPublicKey = aliceIk.publicKey,
            ),
            bobRemote = X3dhRemotePeerKeys(
                identityEncryptionPublicKey = bobIk.publicKey,
                signedPreKeyPublicKey = bobSpk.publicKey,
                signedPreKeyId = "spk-1",
            ),
            bobLocal = X3dhLocalResponderKeys(
                identityEncryptionPrivateKey = bobIk.privateKey,
                identityEncryptionPublicKey = bobIk.publicKey,
                signedPreKeyPrivateKey = bobSpk.privateKey,
                signedPreKeyPublicKey = bobSpk.publicKey,
                signedPreKeyId = "spk-1",
            ),
        )
    }

    private data class FixtureKeys(
        val aliceLocal: X3dhLocalInitiatorKeys,
        val bobRemote: X3dhRemotePeerKeys,
        val bobLocal: X3dhLocalResponderKeys,
    )
}
