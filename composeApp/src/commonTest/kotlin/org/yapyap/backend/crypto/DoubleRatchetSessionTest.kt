package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class DoubleRatchetSessionTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun encryptDecrypt_initiatorToResponder_roundTrip() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val plaintext = "hello ratchet".encodeToByteArray()
        val frame = alice.encrypt(plaintext)
        assertContentEquals(plaintext, bob.decrypt(frame))
    }

    @Test
    fun encryptDecrypt_bidirectional_afterBobReplies() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val a1 = alice.encrypt(byteArrayOf(1))
        bob.decrypt(a1)

        val b1 = bob.encrypt(byteArrayOf(2))
        val a2 = alice.encrypt(byteArrayOf(3))

        assertContentEquals(byteArrayOf(2), alice.decrypt(b1))
        assertContentEquals(byteArrayOf(3), bob.decrypt(a2))
    }

    @Test
    fun decrypt_outOfOrderMessages_usesSkippedKeys() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val m0 = alice.encrypt("m0".encodeToByteArray())
        val m1 = alice.encrypt("m1".encodeToByteArray())
        val m2 = alice.encrypt("m2".encodeToByteArray())

        assertContentEquals("m2".encodeToByteArray(), bob.decrypt(m2))
        assertContentEquals("m0".encodeToByteArray(), bob.decrypt(m0))
        assertContentEquals("m1".encodeToByteArray(), bob.decrypt(m1))
    }

    @Test
    fun ratchetCiphertext_encodeDecode_roundTrip() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val frame = alice.encrypt(byteArrayOf(42))
        val encoded = frame.encode()
        val decoded = RatchetCiphertext.decode(encoded)
        assertContentEquals(byteArrayOf(42), bob.decrypt(decoded))
    }

    @Test
    fun snapshot_restore_preservesDecryptCapability() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val first = alice.encrypt(byteArrayOf(7))
        bob.decrypt(first)

        val restoredBob = DoubleRatchetSession.fromState(crypto, bob.snapshot())
        val second = alice.encrypt(byteArrayOf(8))
        assertContentEquals(byteArrayOf(8), restoredBob.decrypt(second))
    }

    @Test
    fun createInitiator_requiresRemoteDhPublicKey() = runTest {
        assertFailsWith<IllegalStateException> {
            DoubleRatchetSession.createInitiator(
                crypto,
                RatchetBootstrap(sharedSecret = crypto.randomBytes(32)),
            )
        }
    }

    @Test
    fun decrypt_rejectsExcessiveSkipGap() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val first = alice.encrypt(byteArrayOf(1))
        bob.decrypt(first)

        val forged = first.copy(messageNumber = first.messageNumber + 300)
        assertFailsWith<IllegalArgumentException> {
            bob.decrypt(forged)
        }
    }

    private suspend fun testBootstraps(): Pair<RatchetBootstrap, RatchetBootstrap> {
        val bobKeys = crypto.generateEncryptionKeyPair()
        val aliceKeys = crypto.generateEncryptionKeyPair()
        val sharedSecret = crypto.deriveSharedSecret(aliceKeys.privateKey, bobKeys.publicKey)
        return RatchetBootstrap(
            sharedSecret = sharedSecret,
            remoteDhPublicKey = bobKeys.publicKey,
        ) to RatchetBootstrap(
            sharedSecret = sharedSecret,
            localDhPrivateKey = bobKeys.privateKey,
            localDhPublicKey = bobKeys.publicKey,
        )
    }
}
