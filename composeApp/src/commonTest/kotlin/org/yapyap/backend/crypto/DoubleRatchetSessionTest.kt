package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.yapyap.backend.crypto.e2ee.CryptoSessionException
import org.yapyap.backend.crypto.e2ee.DoubleRatchetSession
import org.yapyap.backend.crypto.e2ee.RatchetBootstrap
import org.yapyap.backend.crypto.e2ee.RatchetCiphertext
import org.yapyap.backend.crypto.e2ee.X3dhHandshake
import org.yapyap.backend.crypto.e2ee.X3dhLocalInitiatorKeys
import org.yapyap.backend.crypto.e2ee.X3dhLocalResponderKeys
import org.yapyap.backend.crypto.e2ee.X3dhRemotePeerKeys

class DoubleRatchetSessionTest {

    private val crypto = KmpCryptoProvider()
    private val x3dh = X3dhHandshake(crypto)

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
    fun firstEncrypt_ratchetHeader_matchesX3dhEphemeral() = runTest {
        val (aliceBootstrap, bobBootstrap, ephemeralPublicKey) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val frame = alice.encrypt("signal-aligned".encodeToByteArray())
        assertContentEquals(ephemeralPublicKey, frame.dhPublicKey)
        assertContentEquals("signal-aligned".encodeToByteArray(), bob.decrypt(frame))
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

    @Test
    fun decrypt_rejectsTamperedMessageNumber() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val frame = alice.encrypt(byteArrayOf(1))
        val tampered = frame.copy(messageNumber = frame.messageNumber + 1)

        assertFailsWith<Exception> {
            bob.decrypt(tampered)
        }
    }

    @Test
    fun decrypt_rejectsTamperedDhPublicKey() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val frame = alice.encrypt(byteArrayOf(1))
        val tamperedDh = frame.dhPublicKey.copyOf().also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        }
        val tampered = frame.copy(dhPublicKey = tamperedDh)

        assertFailsWith<Exception> {
            bob.decrypt(tampered)
        }
    }

    @Test
    fun decrypt_rejectsTamperedPreviousChainLength() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val frame = alice.encrypt(byteArrayOf(1))
        val tampered = frame.copy(previousChainLength = frame.previousChainLength + 1)

        assertFailsWith<Exception> {
            bob.decrypt(tampered)
        }
    }

    @Test
    fun decrypt_rejectsTamperedBody() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val frame = alice.encrypt(byteArrayOf(1))
        val tamperedBody = frame.body.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 0xff).toByte()
        }
        val tampered = frame.copy(body = tamperedBody)

        assertFailsWith<Exception> {
            bob.decrypt(tampered)
        }
    }

    @Test
    fun decrypt_outOfOrder_rejectsTamperedHeaderOnSkippedMessage() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val m0 = alice.encrypt("m0".encodeToByteArray())
        alice.encrypt("m1".encodeToByteArray()) // advance send chain

        // Process a later message first so m0 is stored as a skipped key.
        val m2 = alice.encrypt("m2".encodeToByteArray())
        bob.decrypt(m2)

        val tamperedM0 = m0.copy(messageNumber = m0.messageNumber + 1)

        assertFailsWith<Exception> {
            bob.decrypt(tamperedM0)
        }
    }

    @Test
    fun decrypt_lateMessageOnSupersededDhChain_usesSkippedKey() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val m0 = alice.encrypt("m0".encodeToByteArray())
        val m1 = alice.encrypt("m1".encodeToByteArray())
        val m2 = alice.encrypt("m2".encodeToByteArray())
        val oldDh = m0.dhPublicKey.copyOf()

        bob.decrypt(m2)
        val bobReply = bob.encrypt(byteArrayOf(99))
        alice.decrypt(bobReply)
        bob.decrypt(alice.encrypt("m3".encodeToByteArray()))

        assertContentEquals("m0".encodeToByteArray(), bob.decrypt(m0))
        assertContentEquals("m1".encodeToByteArray(), bob.decrypt(m1))
        assertTrue(bob.snapshot().skippedMessageKeys.keys.none { it.dhPublicKey.contentEquals(oldDh) })
    }

    @Test
    fun decrypt_orphanOnSupersededDhChain_failsWithoutSkippedKey() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val m0 = alice.encrypt(byteArrayOf(1))
        alice.encrypt(byteArrayOf(2))
        val m2 = alice.encrypt(byteArrayOf(3))

        bob.decrypt(m2)
        val bobReply = bob.encrypt(byteArrayOf(4))
        alice.decrypt(bobReply)
        bob.decrypt(alice.encrypt(byteArrayOf(5)))

        val orphan = m0.copy(messageNumber = m0.messageNumber + 50)
        assertFailsWith<CryptoSessionException.SupersededDhChain> {
            bob.decrypt(orphan)
        }
    }

    @Test
    fun decrypt_replayAlreadyDecryptedMessage_rejectsWithoutStateMutation() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val frame = alice.encrypt(byteArrayOf(1))
        bob.decrypt(frame)

        val before = bob.snapshot()
        assertFailsWith<CryptoSessionException.Replay> {
            bob.decrypt(frame)
        }
        assertEquals(before.recvMessageNumber, bob.snapshot().recvMessageNumber)
        assertEquals(before.sendMessageNumber, bob.snapshot().sendMessageNumber)
    }

    @Test
    fun decrypt_tamperedBodyOnSkippedMessage_preservesStateAndRetries() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val m0 = alice.encrypt("m0".encodeToByteArray())
        alice.encrypt("m1".encodeToByteArray())
        val m2 = alice.encrypt("m2".encodeToByteArray())
        bob.decrypt(m2)

        val before = bob.snapshot()
        val tamperedBody = m0.body.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 0xff).toByte()
        }
        val tampered = m0.copy(body = tamperedBody)

        assertFailsWith<Exception> {
            bob.decrypt(tampered)
        }
        assertEquals(before.recvMessageNumber, bob.snapshot().recvMessageNumber)
        assertEquals(before.skippedMessageKeys.size, bob.snapshot().skippedMessageKeys.size)

        assertContentEquals("m0".encodeToByteArray(), bob.decrypt(m0))
    }

    @Test
    fun decrypt_tamperedBodyInOrder_preservesState() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val frame = alice.encrypt(byteArrayOf(1))
        val before = bob.snapshot()
        val tamperedBody = frame.body.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 0xff).toByte()
        }

        assertFailsWith<Exception> {
            bob.decrypt(frame.copy(body = tamperedBody))
        }
        assertEquals(before.recvMessageNumber, bob.snapshot().recvMessageNumber)
        assertEquals(before.sendMessageNumber, bob.snapshot().sendMessageNumber)
    }

    @Test
    fun snapshot_restore_preservesSupersededDhSkippedKeys() = runTest {
        val (aliceBootstrap, bobBootstrap) = testBootstraps()
        val alice = DoubleRatchetSession.createInitiator(crypto, aliceBootstrap)
        val bob = DoubleRatchetSession.createResponder(crypto, bobBootstrap)

        val m0 = alice.encrypt("m0".encodeToByteArray())
        alice.encrypt("m1".encodeToByteArray())
        val m2 = alice.encrypt("m2".encodeToByteArray())

        bob.decrypt(m2)
        val bobReply = bob.encrypt(byteArrayOf(7))
        alice.decrypt(bobReply)
        bob.decrypt(alice.encrypt("m3".encodeToByteArray()))

        val snap = bob.snapshot()
        assertTrue(
            snap.skippedMessageKeys.keys.any {
                it.isSupersededDhMarker && it.dhPublicKey.contentEquals(m0.dhPublicKey)
            },
        )
        val restored = DoubleRatchetSession.fromState(crypto, snap)
        assertContentEquals("m0".encodeToByteArray(), restored.decrypt(m0))
    }

    private suspend fun testBootstraps(): Triple<RatchetBootstrap, RatchetBootstrap, ByteArray> {
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
        return Triple(initiator.ratchetBootstrap, responder.ratchetBootstrap, initiator.wire.ephemeralPublicKey)
    }
}
