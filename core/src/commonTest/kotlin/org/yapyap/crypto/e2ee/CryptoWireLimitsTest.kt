package org.yapyap.crypto.e2ee

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.session.*
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class CryptoWireLimitsTest {

    @Test
    fun sessionWireFrame_decode_rejectsOversizedBlob() {
        assertFailsWith<CryptoSessionException.OversizedFrame> {
            testCryptoWireCodec().decodeSessionWireFrame(
                ByteArray(testCryptoLimits().maxSessionWireFrameBytes + 1),
            )
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
            testCryptoWireCodec().decodeRatchetCiphertext(bytes)
        }
    }

    @Test
    fun ratchetCiphertext_decode_rejectsOversizedBodyLength() = runTest {
        val crypto = DefaultCryptoProvider()
        val x3dh = X3dhHandshake(crypto)
        val (aliceBootstrap, bobBootstrap) = DoubleRatchetSessionTestBootstraps.create(crypto, x3dh)
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val frame = alice.encrypt("hello".encodeToByteArray())
        val codec = testCryptoWireCodec()

        val maxBody = testCryptoLimits().maxRatchetBodyBytes
        val tamperedSize = ByteArray(4)
        tamperedSize[0] = ((maxBody + 1) ushr 24).toByte()
        tamperedSize[1] = ((maxBody + 1) ushr 16).toByte()
        tamperedSize[2] = ((maxBody + 1) ushr 8).toByte()
        tamperedSize[3] = (maxBody + 1).toByte()

        val encoded = codec.encode(frame)
        val bodySizeOffset = 4 + frame.dhPublicKey.size + 4 + 4
        val forged = encoded.copyOf()
        tamperedSize.copyInto(forged, bodySizeOffset)

        assertFailsWith<CryptoSessionException.OversizedFrame> {
            codec.decodeRatchetCiphertext(forged)
        }
    }

    @Test
    fun sessionWireFrame_roundTrip_stillWorks() = runTest {
        val crypto = DefaultCryptoProvider()
        val x3dh = X3dhHandshake(crypto)
        val (aliceBootstrap, bobBootstrap) = DoubleRatchetSessionTestBootstraps.create(crypto, x3dh)
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val ratchet = alice.encrypt(byteArrayOf(1, 2, 3))
        val original = SessionWireFrame(
            sessionEpoch = 1,
            sessionGeneration = 1,
            outerHandshake = null,
            ratchet = ratchet,
        )
        val codec = testCryptoWireCodec()

        val decoded = codec.decodeSessionWireFrame(codec.encode(original))
        assertContentEquals(ratchet.body, decoded.ratchet.body)
    }
}

private object DoubleRatchetSessionTestBootstraps {
    suspend fun create(
        crypto: DefaultCryptoProvider,
        x3dh: X3dhHandshake,
    ): Pair<RatchetBootstrap, RatchetBootstrap> {
        val aliceIk = crypto.generateEncryptionKeyPair()
        val bobIk = crypto.generateEncryptionKeyPair()
        val bobSpk = crypto.generateEncryptionKeyPair()
        val ekA = crypto.generateEncryptionKeyPair()
        val initiator = x3dh.initiatorCompute3Dh(
            local = X3dhLocalInitiatorKeys(
                identityEncryptionPrivateKey = aliceIk.privateKey,
                identityEncryptionPublicKey = aliceIk.publicKey,
            ),
            remote = X3dhRemotePeerKeys(
                identityEncryptionPublicKey = bobIk.publicKey,
                signedPreKeyPublicKey = bobSpk.publicKey,
                signedPreKeyId = "spk-test",
            ),
            ephemeral = ekA,
        )
        val responder = x3dh.responderCompute3Dh(
            local = X3dhLocalResponderKeys(
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
