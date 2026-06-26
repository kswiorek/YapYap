package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.yapyap.backend.crypto.e2ee.DoubleRatchetSession
import org.yapyap.backend.crypto.e2ee.InnerSessionControl
import org.yapyap.backend.crypto.e2ee.RatchetCiphertext
import org.yapyap.backend.crypto.e2ee.RatchetInnerPlaintext
import org.yapyap.backend.crypto.e2ee.SessionUpgradePolicy
import org.yapyap.backend.crypto.e2ee.SessionWireFrame
import org.yapyap.backend.crypto.e2ee.X3dhHandshake
import org.yapyap.backend.crypto.e2ee.X3dhLocalInitiatorKeys
import org.yapyap.backend.crypto.e2ee.X3dhMode
import org.yapyap.backend.crypto.e2ee.X3dhRemotePeerKeys
import org.yapyap.backend.crypto.e2ee.X3dhWireInfo
import org.yapyap.backend.db.SessionRole
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionWireCodecTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun sessionWireFrame_encodeDecode_roundTrip() = runTest {
        val ratchet = sampleRatchetCiphertext()
        val wire = X3dhWireInfo(
            ephemeralPublicKey = byteArrayOf(1, 2, 3),
            signedPreKeyId = "spk-1",
            sessionEpoch = 1,
            mode = X3dhMode.THREE_DH,
        )
        val original = SessionWireFrame(
            sessionEpoch = 1,
            outerHandshake = wire,
            ratchet = ratchet,
        )
        val decoded = SessionWireFrame.decode(original.encode())
        assertEquals(original.sessionEpoch, decoded.sessionEpoch)
        assertNotNull(decoded.outerHandshake)
        assertContentEquals(wire.ephemeralPublicKey, decoded.outerHandshake.ephemeralPublicKey)
        assertEquals(wire.signedPreKeyId, decoded.outerHandshake.signedPreKeyId)
        assertContentEquals(ratchet.body, decoded.ratchet.body)
    }

    @Test
    fun innerPlaintext_application_roundTrip() {
        val original = RatchetInnerPlaintext.Payload(byteArrayOf(9, 8, 7))
        val decoded = RatchetInnerPlaintext.decode(original.encode())
        require(decoded is RatchetInnerPlaintext.Payload)
        assertContentEquals(original.bytes, decoded.bytes)
    }

    @Test
    fun innerPlaintext_withOpkOffer_roundTrip() {
        val original = RatchetInnerPlaintext.WithControl(
            bytes = "hello".encodeToByteArray(),
            control = InnerSessionControl.OpkOffer(
                opkId = "opk-1",
                opkPublicKey = byteArrayOf(4, 5, 6),
            ),
        )
        val decoded = RatchetInnerPlaintext.decode(original.encode())
        require(decoded is RatchetInnerPlaintext.WithControl)
        assertContentEquals(original.bytes, decoded.bytes)
        require(decoded.control is InnerSessionControl.OpkOffer)
        assertEquals("opk-1", decoded.control.opkId)
        assertContentEquals(byteArrayOf(4, 5, 6), decoded.control.opkPublicKey)
    }

    private suspend fun sampleRatchetCiphertext(): RatchetCiphertext {
        val aliceIk = crypto.generateEncryptionKeyPair()
        val bobIk = crypto.generateEncryptionKeyPair()
        val bobSpk = crypto.generateEncryptionKeyPair()
        val ekA = crypto.generateEncryptionKeyPair()
        val x3dh = X3dhHandshake(crypto)
        val initiator = x3dh.initiatorCompute3Dh(
            local = X3dhLocalInitiatorKeys(aliceIk.privateKey, aliceIk.publicKey),
            remote = X3dhRemotePeerKeys(bobIk.publicKey, bobSpk.publicKey, "spk-test"),
            ephemeral = ekA,
        )
        val alice = DoubleRatchetSession.createInitiator(crypto, initiator.ratchetBootstrap)
        return alice.encrypt(byteArrayOf(1, 2, 3))
    }
}

class DefaultCryptoSessionManagerTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun epoch1_aliceFirstMessage_bobDecrypts() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))

        val plaintext = "hello bob".encodeToByteArray()
        val frame = alice.encryptMessage(
            bobPeer.device.deviceId,
            plaintext,
        )
        assertEquals(1, frame.sessionEpoch)
        assertNotNull(frame.outerHandshake)

        val opened = bob.decryptMessage(alicePeer.device.deviceId, frame)
        assertContentEquals(plaintext, opened)
    }

    @Test
    fun epoch1_bidirectional_afterPersistedRoundTrip() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))

        val first = alice.encryptMessage(
            bobPeer.device.deviceId,
            byteArrayOf(1),
        )
        bob.decryptMessage(alicePeer.device.deviceId, first)

        val bobReply = bob.encryptMessage(
            alicePeer.device.deviceId,
            byteArrayOf(2),
        )
        val aliceOpened = alice.decryptMessage(bobPeer.device.deviceId, bobReply)
        assertContentEquals(byteArrayOf(2), aliceOpened)

        val aliceAgain = alice.encryptMessage(
            bobPeer.device.deviceId,
            byteArrayOf(3),
        )
        val bobOpened = bob.decryptMessage(alicePeer.device.deviceId, aliceAgain)
        assertContentEquals(byteArrayOf(3), bobOpened)
    }

    @Test
    fun epoch1_outOfOrderDecrypt_works() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(
                bobPeer.device.deviceId,
                byteArrayOf(0),
            ),
        )

        val m0 = alice.encryptMessage(
            bobPeer.device.deviceId,
            "m0".encodeToByteArray(),
        )
        alice.encryptMessage(
            bobPeer.device.deviceId,
            "m1".encodeToByteArray(),
        )
        val m2 = alice.encryptMessage(
            bobPeer.device.deviceId,
            "m2".encodeToByteArray(),
        )

        val opened2 = bob.decryptMessage(alicePeer.device.deviceId, m2)
        assertContentEquals("m2".encodeToByteArray(), opened2)

        val opened0 = bob.decryptMessage(alicePeer.device.deviceId, m0)
        assertContentEquals("m0".encodeToByteArray(), opened0)
    }

    @Test
    fun epoch1_simultaneousInit_bothDecryptAndContinue() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val bobStore = MapBackedCryptoSessionStore()
        val alice = managerForPeer(crypto, alicePeer, bobPeer, aliceStore, InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, bobStore, InMemoryOneTimePreKeyStore(crypto))

        val aliceIsLower = alicePeer.device.deviceId.id < bobPeer.device.deviceId.id
        val lowerPeer = if (aliceIsLower) alicePeer else bobPeer
        val higherPeer = if (aliceIsLower) bobPeer else alicePeer
        val lower = if (aliceIsLower) alice else bob
        val higher = if (aliceIsLower) bob else alice
        val lowerStore = if (aliceIsLower) aliceStore else bobStore
        val higherStore = if (aliceIsLower) bobStore else aliceStore

        val fromAlice = "from alice".encodeToByteArray()
        val fromBob = "from bob".encodeToByteArray()
        val aliceFrame = alice.encryptMessage(bobPeer.device.deviceId, fromAlice)
        val bobFrame = bob.encryptMessage(alicePeer.device.deviceId, fromBob)
        assertNotNull(aliceFrame.outerHandshake)
        assertNotNull(bobFrame.outerHandshake)

        assertContentEquals(fromAlice, bob.decryptMessage(alicePeer.device.deviceId, aliceFrame))
        assertContentEquals(fromBob, alice.decryptMessage(bobPeer.device.deviceId, bobFrame))

        assertEquals(
            SessionRole.RESPONDER,
            lowerStore.loadCanonical(higherPeer.device.deviceId, sessionEpoch = 1)!!.meta.role,
        )
        assertEquals(
            SessionRole.INITIATOR,
            higherStore.loadCanonical(lowerPeer.device.deviceId, sessionEpoch = 1)!!.meta.role,
        )
        assertFalse(
            lowerStore.loadSessions(higherPeer.device.deviceId, sessionEpoch = 1)
                .single { it.meta.role == SessionRole.INITIATOR }
                .canonical,
        )
        assertFalse(
            higherStore.loadSessions(lowerPeer.device.deviceId, sessionEpoch = 1)
                .single { it.meta.role == SessionRole.RESPONDER }
                .canonical,
        )

        assertContentEquals(
            byteArrayOf(10),
            lower.decryptMessage(
                higherPeer.device.deviceId,
                higher.encryptMessage(lowerPeer.device.deviceId, byteArrayOf(10)),
            ),
        )
        assertContentEquals(
            byteArrayOf(11),
            higher.decryptMessage(
                lowerPeer.device.deviceId,
                lower.encryptMessage(higherPeer.device.deviceId, byteArrayOf(11)),
            ),
        )
    }

    @Test
    fun epoch2_opkOffer_createsSecondSessionOnAlice() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOneTimePreKeyStore(crypto)
        val alice = managerForPeer(crypto, alicePeer, bobPeer, aliceStore, InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = MapBackedCryptoSessionStore(),
            oneTimePreKeyStore = bobOpkStore,
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(
                bobPeer.device.deviceId,
                byteArrayOf(1),
            ),
        )

        val bobReply = bob.encryptMessage(
            alicePeer.device.deviceId,
            byteArrayOf(2),
        )
        alice.decryptMessage(bobPeer.device.deviceId, bobReply)

        assertNotNull(aliceStore.loadCanonical(bobPeer.device.deviceId, sessionEpoch = 2))
    }

    @Test
    fun epoch2_aliceEncryptsBobDecrypts_afterOpkOffer() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOneTimePreKeyStore(crypto)
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = bobOpkStore,
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )

        val epoch1Frame = alice.encryptMessage(
            bobPeer.device.deviceId,
            byteArrayOf(1),
        )

        bob.decryptMessage(
            alicePeer.device.deviceId,
            epoch1Frame,
        )
        alice.decryptMessage(
            bobPeer.device.deviceId,
            bob.encryptMessage(
                alicePeer.device.deviceId,
                byteArrayOf(2),
            ),
        )

        val epoch2Frame = alice.encryptMessage(
            bobPeer.device.deviceId,
            byteArrayOf(3),
        )
        assertEquals(2, epoch2Frame.sessionEpoch)
        assertNotNull(epoch2Frame.outerHandshake)
        assertEquals(X3dhMode.FOUR_DH, epoch2Frame.outerHandshake.mode)
        assertNotEquals(epoch2Frame.outerHandshake.ephemeralPublicKey, epoch1Frame.outerHandshake!!.ephemeralPublicKey)

        val opened = bob.decryptMessage(alicePeer.device.deviceId, epoch2Frame)
        assertContentEquals(byteArrayOf(3), opened)
        assertNotNull(bobStore.loadCanonical(alicePeer.device.deviceId, sessionEpoch = 2))
    }

    @Test
    fun epoch1_firstMessageLost_secondMessageStillCarriesHandshake() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))

        alice.encryptMessage(bobPeer.device.deviceId, "lost".encodeToByteArray())

        val second = alice.encryptMessage(bobPeer.device.deviceId, "delivered".encodeToByteArray())
        assertNotNull(second.outerHandshake)

        val opened = bob.decryptMessage(alicePeer.device.deviceId, second)
        assertContentEquals("delivered".encodeToByteArray(), opened)
    }

    @Test
    fun epoch1_message2ArrivesBeforeMessage1_bobBootstrapFromSecond() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))

        val m0 = alice.encryptMessage(bobPeer.device.deviceId, "m0".encodeToByteArray())
        alice.encryptMessage(bobPeer.device.deviceId, "m1".encodeToByteArray())
        val m2 = alice.encryptMessage(bobPeer.device.deviceId, "m2".encodeToByteArray())
        assertNotNull(m2.outerHandshake)

        val opened2 = bob.decryptMessage(alicePeer.device.deviceId, m2)
        assertContentEquals("m2".encodeToByteArray(), opened2)

        val opened0 = bob.decryptMessage(alicePeer.device.deviceId, m0)
        assertContentEquals("m0".encodeToByteArray(), opened0)
    }

    @Test
    fun epoch1_stopsAttachingHandshake_afterPeerReply() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))

        val first = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        assertNotNull(first.outerHandshake)

        bob.decryptMessage(alicePeer.device.deviceId, first)
        val bobReply = bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2))
        alice.decryptMessage(bobPeer.device.deviceId, bobReply)

        val followUp = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(3))
        assertNull(followUp.outerHandshake)

        val opened = bob.decryptMessage(alicePeer.device.deviceId, followUp)
        assertContentEquals(byteArrayOf(3), opened)
    }

    @Test
    fun epoch1_bobDecrypt_establishesCanonicalResponderSession() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, bobStore, InMemoryOneTimePreKeyStore(crypto))

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )

        val canonical = bobStore.loadCanonical(alicePeer.device.deviceId, sessionEpoch = 1)
        assertNotNull(canonical)
        assertTrue(canonical.canonical)
        assertEquals(SessionRole.RESPONDER, canonical.meta.role)
        assertEquals(1, bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1).size)
    }

    @Test
    fun epoch1_tamperedBootstrapMessage_leavesNoPersistedSession() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, bobStore, InMemoryOneTimePreKeyStore(crypto))

        val frame = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        val tampered = frame.copy(
            ratchet = frame.ratchet.copy(body = frame.ratchet.body.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() }),
        )

        assertFailsWith<Exception> {
            bob.decryptMessage(alicePeer.device.deviceId, tampered)
        }
        assertEquals(0, bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1).size)
        assertNull(bobStore.loadCanonical(alicePeer.device.deviceId, sessionEpoch = 1))
    }

    @Test
    fun epoch1_bobEncrypt_reusesResponderSessionWithoutDuplicateInitiator() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val alice = managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOneTimePreKeyStore(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, bobStore, InMemoryOneTimePreKeyStore(crypto))

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )
        bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2))

        val sessions = bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1)
        assertEquals(1, sessions.size)
        assertEquals(SessionRole.RESPONDER, sessions.single().meta.role)
        assertTrue(sessions.single().canonical)
    }
}
