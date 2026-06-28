package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.yapyap.backend.crypto.e2ee.CryptoWireLimits
import org.yapyap.backend.crypto.e2ee.RatchetCiphertext
import org.yapyap.backend.crypto.e2ee.SessionWireFrame

class CryptoWireLimitsTest {

    @Test
    fun sessionWireFrame_decode_rejectsOversizedBlob() {
        assertFailsWith<IllegalArgumentException> {
            SessionWireFrame.decode(ByteArray(CryptoWireLimits.MAX_SESSION_WIRE_FRAME_BYTES + 1))
        }
    }

    @Test
    fun ratchetCiphertext_decode_rejectsOversizedDhPublicKeyLength() {
        val bytes = ByteArray(8)
        bytes[0] = 0
        bytes[1] = 0
        bytes[2] = 0
        bytes[3] = (CryptoWireLimits.MAX_DH_PUBLIC_KEY_BYTES + 1).toByte()

        assertFailsWith<IllegalArgumentException> {
            RatchetCiphertext.decode(bytes)
        }
    }

    @Test
    fun ratchetCiphertext_decode_rejectsOversizedBodyLength() = runTest {
        val crypto = KmpCryptoProvider()
        val x3dh = org.yapyap.backend.crypto.e2ee.X3dhHandshake(crypto)
        val (aliceBootstrap, bobBootstrap) = DoubleRatchetSessionTestBootstraps.create(crypto, x3dh)
        val alice = org.yapyap.backend.crypto.e2ee.DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val frame = alice.encrypt("hello".encodeToByteArray())

        val tamperedSize = ByteArray(4)
        tamperedSize[0] = ((CryptoWireLimits.MAX_RATCHET_BODY_BYTES + 1) ushr 24).toByte()
        tamperedSize[1] = ((CryptoWireLimits.MAX_RATCHET_BODY_BYTES + 1) ushr 16).toByte()
        tamperedSize[2] = ((CryptoWireLimits.MAX_RATCHET_BODY_BYTES + 1) ushr 8).toByte()
        tamperedSize[3] = (CryptoWireLimits.MAX_RATCHET_BODY_BYTES + 1).toByte()

        val encoded = frame.encode()
        val bodySizeOffset = 4 + frame.dhPublicKey.size + 4 + 4
        val forged = encoded.copyOf()
        tamperedSize.copyInto(forged, bodySizeOffset)

        assertFailsWith<IllegalArgumentException> {
            RatchetCiphertext.decode(forged)
        }
    }

    @Test
    fun sessionWireFrame_roundTrip_stillWorks() = runTest {
        val crypto = KmpCryptoProvider()
        val x3dh = org.yapyap.backend.crypto.e2ee.X3dhHandshake(crypto)
        val (aliceBootstrap, bobBootstrap) = DoubleRatchetSessionTestBootstraps.create(crypto, x3dh)
        val alice = org.yapyap.backend.crypto.e2ee.DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val ratchet = alice.encrypt(byteArrayOf(1, 2, 3))
        val original = SessionWireFrame(
            sessionEpoch = 1,
            sessionGeneration = 1,
            outerHandshake = null,
            ratchet = ratchet,
        )

        val decoded = SessionWireFrame.decode(original.encode())
        assertContentEquals(ratchet.body, decoded.ratchet.body)
    }
}

private object DoubleRatchetSessionTestBootstraps {
    suspend fun create(
        crypto: KmpCryptoProvider,
        x3dh: org.yapyap.backend.crypto.e2ee.X3dhHandshake,
    ): Pair<org.yapyap.backend.crypto.e2ee.RatchetBootstrap, org.yapyap.backend.crypto.e2ee.RatchetBootstrap> {
        val aliceIk = crypto.generateEncryptionKeyPair()
        val bobIk = crypto.generateEncryptionKeyPair()
        val bobSpk = crypto.generateEncryptionKeyPair()
        val ekA = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute3Dh(
            local = org.yapyap.backend.crypto.e2ee.X3dhLocalInitiatorKeys(
                identityEncryptionPrivateKey = aliceIk.privateKey,
                identityEncryptionPublicKey = aliceIk.publicKey,
            ),
            remote = org.yapyap.backend.crypto.e2ee.X3dhRemotePeerKeys(
                identityEncryptionPublicKey = bobIk.publicKey,
                signedPreKeyPublicKey = bobSpk.publicKey,
                signedPreKeyId = "spk-test",
            ),
            ephemeral = ekA,
        )
        val responder = x3dh.responderCompute3Dh(
            local = org.yapyap.backend.crypto.e2ee.X3dhLocalResponderKeys(
                identityEncryptionPrivateKey = bobIk.privateKey,
                identityEncryptionPublicKey = bobIk.publicKey,
                signedPreKeyPrivateKey = bobSpk.privateKey,
                signedPreKeyPublicKey = bobSpk.publicKey,
                signedPreKeyId = "spk-test",
            ),
            remoteIdentityEncryptionPublicKey = aliceIk.publicKey,
            wire = initiator.wire,
        )
        return initiator.ratchetBootstrap to responder.ratchetBootstrap
    }
}
