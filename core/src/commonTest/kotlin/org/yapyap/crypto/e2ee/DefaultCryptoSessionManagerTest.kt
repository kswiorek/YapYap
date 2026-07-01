package org.yapyap.crypto.e2ee

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.persistence.db.OpkStatus
import org.yapyap.persistence.key.FailingAllocateOpkRepository
import org.yapyap.persistence.key.InMemoryOpkRepository
import org.yapyap.time.FixedEpochSecondsProvider
import kotlin.test.*

class SessionWireTypesTest {

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
        assertEquals(original.sessionGeneration, decoded.sessionGeneration)
        assertNotNull(decoded.outerHandshake)
        assertContentEquals(wire.ephemeralPublicKey, decoded.outerHandshake.ephemeralPublicKey)
        assertEquals(wire.sessionEpoch, decoded.outerHandshake.sessionEpoch)
        assertEquals(wire.sessionGeneration, decoded.outerHandshake.sessionGeneration)
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
        val binding = ByteArray(OpkOfferBinding.BINDING_LENGTH) { 7 }
        val original = RatchetInnerPlaintext.WithControl(
            bytes = "hello".encodeToByteArray(),
            control = InnerSessionControl.OpkOffer(
                sessionEpoch = 1,
                sessionGeneration = 2,
                opkId = "opk-1",
                opkPublicKey = byteArrayOf(4, 5, 6),
                sessionBinding = binding,
            ),
        )
        val decoded = RatchetInnerPlaintext.decode(original.encode())
        require(decoded is RatchetInnerPlaintext.WithControl)
        assertContentEquals(original.bytes, decoded.bytes)
        require(decoded.control is InnerSessionControl.OpkOffer)
        assertEquals(1, decoded.control.sessionEpoch)
        assertEquals(2, decoded.control.sessionGeneration)
        assertEquals("opk-1", decoded.control.opkId)
        assertContentEquals(byteArrayOf(4, 5, 6), decoded.control.opkPublicKey)
        assertContentEquals(binding, decoded.control.sessionBinding)
    }

    @Test
    fun opkOfferBinding_matchesAcrossPeers() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val initiatorEphemeral = byteArrayOf(0x41, 0x42)
        val fromAlice = OpkOfferBinding.compute(
            crypto = crypto,
            localDeviceId = alicePeer.device.deviceId,
            peerDeviceId = bobPeer.device.deviceId,
            sessionEpoch = 1,
            sessionGeneration = 3,
            handshakeSpkId = "spk-alice",
            initiatorEphemeralPublicKey = initiatorEphemeral,
        )
        val fromBob = OpkOfferBinding.compute(
            crypto = crypto,
            localDeviceId = bobPeer.device.deviceId,
            peerDeviceId = alicePeer.device.deviceId,
            sessionEpoch = 1,
            sessionGeneration = 3,
            handshakeSpkId = "spk-alice",
            initiatorEphemeralPublicKey = initiatorEphemeral,
        )
        assertContentEquals(fromAlice, fromBob)
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
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob =
            managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))

        val plaintext = "hello bob".encodeToByteArray()
        val frame = alice.encryptMessage(
            bobPeer.device.deviceId,
            plaintext,
        )
        assertEquals(1, frame.sessionEpoch)
        assertNotNull(frame.outerHandshake)
        assertContentEquals(frame.outerHandshake.ephemeralPublicKey, frame.ratchet.dhPublicKey)

        val opened = bob.decryptMessage(alicePeer.device.deviceId, frame)
        assertContentEquals(plaintext, opened)
    }

    @Test
    fun epoch1_bidirectional_afterPersistedRoundTrip() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob =
            managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))

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
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob =
            managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))

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
        val alice = managerForPeer(
            crypto, alicePeer, bobPeer, aliceStore, InMemoryOpkRepository(crypto),
            timeProvider = FixedEpochSecondsProvider(100_000L),
        )
        val bob = managerForPeer(crypto, bobPeer, alicePeer, bobStore, InMemoryOpkRepository(crypto))

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
            lowerStore.loadActiveCanonical(higherPeer.device.deviceId, sessionEpoch = 1)!!.meta.role,
        )
        assertEquals(
            SessionRole.INITIATOR,
            higherStore.loadActiveCanonical(lowerPeer.device.deviceId, sessionEpoch = 1)!!.meta.role,
        )
        assertEquals(
            SessionStatus.SUPERSEDED,
            lowerStore.loadSessions(higherPeer.device.deviceId, sessionEpoch = 1)
                .single { it.meta.role == SessionRole.INITIATOR }
                .meta.status,
        )
        assertEquals(
            SessionStatus.SUPERSEDED,
            higherStore.loadSessions(lowerPeer.device.deviceId, sessionEpoch = 1)
                .single { it.meta.role == SessionRole.RESPONDER }
                .meta.status,
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
    fun initiatorEphemeralPrivateKey_notPersistedAfterBootstrap() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val alice = managerForPeer(crypto, alicePeer, bobPeer, aliceStore, InMemoryOpkRepository(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = MapBackedCryptoSessionStore(),
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )

        alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        val epoch1 = aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!
        assertNull(epoch1.meta.initiatorEphemeralPrivateKey)
        assertNotNull(epoch1.meta.initiatorEphemeralPublicKey)

        establishEpoch1Initiator(alice, bob, alicePeer, bobPeer)
        deliverFirstOpkOfferToAlice(alice, bob, alicePeer, bobPeer)
        val pendingEpoch2 = aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 2).single()
        assertNull(pendingEpoch2.meta.initiatorEphemeralPrivateKey)
        assertNotNull(pendingEpoch2.meta.initiatorEphemeralPublicKey)
    }

    @Test
    fun epoch2_opkOffer_createsSecondSessionOnAlice() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOpkRepository(crypto)
        val alice = managerForPeer(
            crypto, alicePeer, bobPeer, aliceStore, InMemoryOpkRepository(crypto),
            timeProvider = FixedEpochSecondsProvider(100_000L),
        )
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

        val pendingEpoch2 = aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 2).singleOrNull()
        assertNotNull(pendingEpoch2)
        assertEquals(SessionStatus.PENDING, pendingEpoch2.meta.status)
        assertNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 2))
    }

    @Test
    fun epoch2_aliceEncryptsBobDecrypts_afterOpkOffer() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOpkRepository(crypto)
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
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
        promoteAliceEpoch2Encrypt(alice, bob, alicePeer, bobPeer)

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
        assertNotNull(bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 2))
        assertNull(bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1))
        assertEquals(
            SessionStatus.SUPERSEDED,
            bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1).single().meta.status,
        )
    }

    @Test
    fun epoch2_reoffersSameOpkOnSubsequentMessages() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOpkRepository(crypto)
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = bobOpkStore,
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )
        bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2))
        val firstOfferedOpkId = bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)!!.meta.offeredOpkId!!

        bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(3))
        val secondOfferedOpkId = bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)!!.meta.offeredOpkId!!

        assertEquals(firstOfferedOpkId, secondOfferedOpkId)
        assertEquals(OpkStatus.OFFERED, bobOpkStore.status(firstOfferedOpkId))
    }

    @Test
    fun epoch2_ignoresOfferDecryptedOnSupersededGeneration() = runTest {
        val testTime = FixedEpochSecondsProvider(100_000L)
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val config = CryptoSessionConfig(
            canonicalIdleSupersedeSeconds = 60,
            supersededRetentionSeconds = 60 * 60,
        )
        val alice = managerForPeer(
            crypto = crypto,
            local = alicePeer,
            peer = bobPeer,
            sessionStore = aliceStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            sessionConfig = config,
            timeProvider = testTime,
        )
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = MapBackedCryptoSessionStore(),
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
            sessionConfig = config,
            timeProvider = testTime,
        )

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )
        val gen1OfferFrame = bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2))

        val stale = aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!
        aliceStore.save(stale.copy(meta = stale.meta.copy(updatedAtEpochSeconds = 0L)))
        cryptoHousekeepingFor(
            sessionStore = aliceStore,
            opkRepository = InMemoryOpkRepository(crypto),
            sessionConfig = config,
            timeProvider = testTime,
        ).run(nowEpochSeconds = 100_000L)
        assertNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1))

        alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(3))
        assertEquals(2, aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!.meta.sessionGeneration)

        alice.decryptMessage(bobPeer.device.deviceId, gen1OfferFrame)

        assertNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 2))
    }

    @Test
    fun epoch2_rejectsOfferWithInvalidBinding() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val bobStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOpkRepository(crypto)
        val alice = managerForPeer(crypto, alicePeer, bobPeer, aliceStore, InMemoryOpkRepository(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = bobOpkStore,
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )

        val bobSession = bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)!!
        val ratchet = DoubleRatchetSession.fromState(crypto, bobSession.ratchetState)
        val tamperedOffer = RatchetInnerPlaintext.WithControl(
            bytes = byteArrayOf(9),
            control = InnerSessionControl.OpkOffer(
                sessionEpoch = 1,
                sessionGeneration = 1,
                opkId = "tampered-opk",
                opkPublicKey = byteArrayOf(1, 2, 3),
                sessionBinding = ByteArray(OpkOfferBinding.BINDING_LENGTH),
            ),
        )
        val frame = SessionWireFrame(
            sessionEpoch = 1,
            sessionGeneration = 1,
            outerHandshake = null,
            ratchet = ratchet.encrypt(tamperedOffer.encode()),
        )

        alice.decryptMessage(bobPeer.device.deviceId, frame)

        assertNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 2))
    }

    @Test
    fun epoch2_bootstrap_rejectsMismatchedSignedPreKeyId() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOpkRepository(crypto)
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = bobOpkStore,
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )
        alice.decryptMessage(
            bobPeer.device.deviceId,
            bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2)),
        )
        promoteAliceEpoch2Encrypt(alice, bob, alicePeer, bobPeer)

        val epoch2Frame = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(3))
        val mismatched = epoch2Frame.copy(
            outerHandshake = epoch2Frame.outerHandshake!!.copy(signedPreKeyId = "wrong-spk"),
        )

        assertFailsWith<CryptoSessionException.NoSession> {
            bob.decryptMessage(alicePeer.device.deviceId, mismatched)
        }
        assertNull(bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 2))
        assertEquals(0, bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 2).size)
    }

    @Test
    fun epoch2_confirmed_marksEpoch1SupersededOnInitiatorAfterPeerReply() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOpkRepository(crypto)
        val alice = managerForPeer(crypto, alicePeer, bobPeer, aliceStore, InMemoryOpkRepository(crypto))
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
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )
        alice.decryptMessage(
            bobPeer.device.deviceId,
            bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2)),
        )
        assertEquals(SessionStatus.ACTIVE, aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!.meta.status)
        assertEquals(
            SessionStatus.PENDING,
            aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 2).single().meta.status,
        )

        promoteAliceEpoch2Encrypt(alice, bob, alicePeer, bobPeer)

        val epoch2Frame = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(3))
        bob.decryptMessage(alicePeer.device.deviceId, epoch2Frame)
        assertEquals(SessionStatus.ACTIVE, aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!.meta.status)

        alice.decryptMessage(
            bobPeer.device.deviceId,
            bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(4)),
        )
        assertNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1))
        assertEquals(
            SessionStatus.SUPERSEDED,
            aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 1).single().meta.status,
        )
    }

    @Test
    fun epoch1_firstMessageLost_secondMessageStillCarriesHandshake() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob =
            managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))

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
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob =
            managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))

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
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob =
            managerForPeer(crypto, bobPeer, alicePeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))

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
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, bobStore, InMemoryOpkRepository(crypto))

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )

        val canonical = bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)
        assertNotNull(canonical)
        assertTrue(canonical.canonical)
        assertEquals(SessionRole.RESPONDER, canonical.meta.role)
        assertEquals(1, bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1).size)
    }

    @Test
    fun epoch2_supersedeEpoch1_retentionMeasuredFromSupersedeTime() = runTest {
        val testTime = FixedEpochSecondsProvider(100_000L)
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val config = CryptoSessionConfig(
            canonicalIdleSupersedeSeconds = 60 * 60 * 24,
            supersededRetentionSeconds = 60,
        )
        val bobOpkStore = InMemoryOpkRepository(crypto)
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = bobOpkStore,
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
            sessionConfig = config,
            timeProvider = testTime,
        )

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )
        alice.decryptMessage(
            bobPeer.device.deviceId,
            bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2)),
        )

        val staleEpoch1 = bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)!!
        bobStore.save(staleEpoch1.copy(meta = staleEpoch1.meta.copy(updatedAtEpochSeconds = 0L)))

        promoteAliceEpoch2Encrypt(alice, bob, alicePeer, bobPeer)
        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(3)),
        )

        val supersededEpoch1 = bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1).single()
        assertEquals(SessionStatus.SUPERSEDED, supersededEpoch1.meta.status)
        assertEquals(100_000L, supersededEpoch1.meta.updatedAtEpochSeconds)

        maintainPeerSessions(
            sessionStore = bobStore,
            sessionConfig = config,
            peerDeviceId = alicePeer.device.deviceId,
            nowEpochSeconds = 100_030L,
        )
        assertEquals(1, bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1).size)

        maintainPeerSessions(
            sessionStore = bobStore,
            sessionConfig = config,
            peerDeviceId = alicePeer.device.deviceId,
            nowEpochSeconds = 100_061L,
        )
        assertEquals(0, bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1).size)
    }

    @Test
    fun epoch1_bootstrap_rejectsMismatchedWireSessionEpoch() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, bobStore, InMemoryOpkRepository(crypto))

        val frame = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        val mismatched = frame.copy(
            outerHandshake = frame.outerHandshake!!.copy(sessionEpoch = 2),
        )

        val error = assertFailsWith<CryptoSessionException.HandshakeMismatch> {
            bob.decryptMessage(alicePeer.device.deviceId, mismatched)
        }
        assertTrue(error.message!!.contains("sessionEpoch mismatch"))
        assertEquals(0, bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1).size)
        assertNull(bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1))
    }

    @Test
    fun epoch1_tamperedBootstrapMessage_leavesNoPersistedSession() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, bobStore, InMemoryOpkRepository(crypto))

        val frame = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        val tampered = frame.copy(
            ratchet = frame.ratchet.copy(body = frame.ratchet.body.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() }),
        )

        assertFailsWith<Exception> {
            bob.decryptMessage(alicePeer.device.deviceId, tampered)
        }
        assertEquals(0, bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1).size)
        assertNull(bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1))
    }

    @Test
    fun epoch1_bobEncrypt_reusesResponderSessionWithoutDuplicateInitiator() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob = managerForPeer(crypto, bobPeer, alicePeer, bobStore, InMemoryOpkRepository(crypto))

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

    @Test
    fun epoch1_encrypt_skipsSupersededCanonicalSession() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val alice = managerForPeer(
            crypto, alicePeer, bobPeer, aliceStore, InMemoryOpkRepository(crypto),
            timeProvider = FixedEpochSecondsProvider(100_000L),
        )

        alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        aliceStore.markSuperseded(
            bobPeer.device.deviceId,
            sessionEpoch = 1,
            role = SessionRole.INITIATOR,
            sessionGeneration = 1,
            updatedAtEpochSeconds = 99_950L,
        )
        assertNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1))

        val second = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(2))
        assertNotNull(second.outerHandshake)
        assertEquals(2, second.sessionGeneration)

        val sessions = aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 1)
        assertEquals(2, sessions.size)
        assertEquals(
            SessionStatus.SUPERSEDED,
            sessions.single { it.meta.sessionGeneration == 1 }.meta.status,
        )

        val active = aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)
        assertNotNull(active)
        assertEquals(SessionStatus.ACTIVE, active.meta.status)
        assertEquals(SessionRole.INITIATOR, active.meta.role)
    }

    @Test
    fun peerMaintenance_idleCanonicalSupersededAfterEncrypt() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val config = CryptoSessionConfig(
            canonicalIdleSupersedeSeconds = 60,
            supersededRetentionSeconds = 60 * 60,
        )
        val alice = managerForPeer(
            crypto = crypto,
            local = alicePeer,
            peer = bobPeer,
            sessionStore = aliceStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            sessionConfig = config,
        )

        alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        val stale = aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!
        aliceStore.save(
            stale.copy(
                meta = stale.meta.copy(updatedAtEpochSeconds = 0L),
            ),
        )

        cryptoHousekeepingFor(
            sessionStore = aliceStore,
            opkRepository = InMemoryOpkRepository(crypto),
            sessionConfig = config,
        ).run(nowEpochSeconds = 100_000L)

        assertNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1))
        assertEquals(
            SessionStatus.SUPERSEDED,
            aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 1).single().meta.status,
        )
    }

    @Test
    fun peerMaintenance_prunesStaleSupersededRow() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val config = CryptoSessionConfig(
            canonicalIdleSupersedeSeconds = 60 * 60 * 24,
            supersededRetentionSeconds = 60,
        )
        val alice = managerForPeer(
            crypto = crypto,
            local = alicePeer,
            peer = bobPeer,
            sessionStore = aliceStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            sessionConfig = config,
        )

        alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        val initiator = aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!
        aliceStore.save(
            initiator.copy(
                meta = initiator.meta.copy(
                    role = SessionRole.RESPONDER,
                    status = SessionStatus.SUPERSEDED,
                    updatedAtEpochSeconds = 0L,
                ),
                canonical = false,
            ),
        )
        assertEquals(2, aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 1).size)

        cryptoHousekeepingFor(
            sessionStore = aliceStore,
            opkRepository = InMemoryOpkRepository(crypto),
            sessionConfig = config,
        ).run(nowEpochSeconds = 100_000L)

        assertEquals(1, aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 1).size)
        assertEquals(SessionRole.INITIATOR, aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!.meta.role)
    }

    @Test
    fun supersededSession_stillDecryptsLateGen1Message() = runTest {
        val testTime = FixedEpochSecondsProvider(100_000L)
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val bobStore = MapBackedCryptoSessionStore()
        val config = CryptoSessionConfig(
            canonicalIdleSupersedeSeconds = 60,
            supersededRetentionSeconds = 60 * 60,
        )
        val alice = managerForPeer(
            crypto = crypto,
            local = alicePeer,
            peer = bobPeer,
            sessionStore = aliceStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            sessionConfig = config,
            timeProvider = testTime,
        )
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            sessionConfig = config,
            timeProvider = testTime,
        )

        val first = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        bob.decryptMessage(alicePeer.device.deviceId, first)

        val lateFromBob = bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(7))
        assertEquals(1, lateFromBob.sessionGeneration)

        val stale = aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!
        aliceStore.save(stale.copy(meta = stale.meta.copy(updatedAtEpochSeconds = 0L)))
        maintainPeerSessions(
            sessionStore = aliceStore,
            sessionConfig = config,
            peerDeviceId = bobPeer.device.deviceId,
            nowEpochSeconds = 100_000L,
        )
        assertNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1))

        val reset = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(2))
        assertEquals(2, reset.sessionGeneration)
        bob.decryptMessage(alicePeer.device.deviceId, reset)

        val opened = alice.decryptMessage(bobPeer.device.deviceId, lateFromBob)
        assertContentEquals(byteArrayOf(7), opened)
        assertEquals(
            SessionStatus.SUPERSEDED,
            aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 1)
                .single { it.meta.sessionGeneration == 1 }
                .meta.status,
        )
    }

    @Test
    fun idleSupersede_newGenerationHandshakeRoundTrip() = runTest {
        val testTime = FixedEpochSecondsProvider(100_000L)
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val bobStore = MapBackedCryptoSessionStore()
        val config = CryptoSessionConfig(
            canonicalIdleSupersedeSeconds = 60,
            supersededRetentionSeconds = 60 * 60,
        )
        val alice = managerForPeer(
            crypto = crypto,
            local = alicePeer,
            peer = bobPeer,
            sessionStore = aliceStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            sessionConfig = config,
            timeProvider = testTime,
        )
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            sessionConfig = config,
            timeProvider = testTime,
        )

        val first = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        bob.decryptMessage(alicePeer.device.deviceId, first)

        val stale = aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!
        aliceStore.save(stale.copy(meta = stale.meta.copy(updatedAtEpochSeconds = 0L)))
        maintainPeerSessions(
            sessionStore = aliceStore,
            sessionConfig = config,
            peerDeviceId = bobPeer.device.deviceId,
            nowEpochSeconds = 100_000L,
        )
        assertNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1))

        val reset = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(2))
        assertEquals(2, reset.sessionGeneration)
        assertNotNull(reset.outerHandshake)

        bob.decryptMessage(alicePeer.device.deviceId, reset)
        val reply = bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(3))
        val opened = alice.decryptMessage(bobPeer.device.deviceId, reply)
        assertContentEquals(byteArrayOf(3), opened)

        val aliceSessions = aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 1)
        assertEquals(2, aliceSessions.size)
        assertEquals(
            SessionStatus.SUPERSEDED,
            aliceSessions.single { it.meta.sessionGeneration == 1 }.meta.status,
        )
        assertEquals(2, aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!.meta.sessionGeneration)
    }

    @Test
    fun inboundGenerationReset_deferredUntilDecryptSucceeds() = runTest {
        val testTime = FixedEpochSecondsProvider(100_000L)
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val bobStore = MapBackedCryptoSessionStore()
        val config = CryptoSessionConfig(
            canonicalIdleSupersedeSeconds = 60,
            supersededRetentionSeconds = 60 * 60,
        )
        val alice = managerForPeer(
            crypto = crypto,
            local = alicePeer,
            peer = bobPeer,
            sessionStore = aliceStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            sessionConfig = config,
            timeProvider = testTime,
        )
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            sessionConfig = config,
            timeProvider = testTime,
        )

        val first = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1))
        bob.decryptMessage(alicePeer.device.deviceId, first)

        val stale = aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1)!!
        aliceStore.save(stale.copy(meta = stale.meta.copy(updatedAtEpochSeconds = 0L)))
        maintainPeerSessions(
            sessionStore = aliceStore,
            sessionConfig = config,
            peerDeviceId = bobPeer.device.deviceId,
            nowEpochSeconds = 100_000L,
        )

        val reset = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(2))
        assertEquals(2, reset.sessionGeneration)
        val tamperedBody = reset.ratchet.body.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 0xff).toByte()
        }
        val tampered = reset.copy(ratchet = reset.ratchet.copy(body = tamperedBody))

        assertFailsWith<Exception> {
            bob.decryptMessage(alicePeer.device.deviceId, tampered)
        }

        val bobCanonical = bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)
        assertNotNull(bobCanonical)
        assertEquals(1, bobCanonical.meta.sessionGeneration)
        assertEquals(SessionStatus.ACTIVE, bobCanonical.meta.status)

        val opened = bob.decryptMessage(alicePeer.device.deviceId, reset)
        assertContentEquals(byteArrayOf(2), opened)
        assertEquals(
            SessionStatus.SUPERSEDED,
            bobStore.loadSessions(alicePeer.device.deviceId, sessionEpoch = 1)
                .single { it.meta.sessionGeneration == 1 }
                .meta.status,
        )
        assertEquals(2, bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)!!.meta.sessionGeneration)
    }

    @Test
    fun peerMaintenance_pruneRespectsRetention_perGeneration() = runTest {
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val config = CryptoSessionConfig(
            canonicalIdleSupersedeSeconds = 60 * 60 * 24,
            supersededRetentionSeconds = 60,
        )

        val gen1 = CryptoSessionRecord(
            peerDeviceId = bobPeer.device.deviceId,
            sessionEpoch = 1,
            canonical = false,
            ratchetState = sampleRatchetState(),
            meta = CryptoSessionMeta(
                role = SessionRole.INITIATOR,
                x3dhMode = X3dhMode.THREE_DH,
                handshakeSpkId = "spk",
                status = SessionStatus.SUPERSEDED,
                sessionGeneration = 1,
                createdAtEpochSeconds = 0L,
                updatedAtEpochSeconds = 0L,
            ),
        )
        val gen2 = gen1.copy(
            meta = gen1.meta.copy(
                status = SessionStatus.SUPERSEDED,
                sessionGeneration = 2,
                updatedAtEpochSeconds = 99_950L,
            ),
        )
        val active = gen1.copy(
            meta = gen1.meta.copy(
                status = SessionStatus.ACTIVE,
                sessionGeneration = 3,
                updatedAtEpochSeconds = 100_000L,
            ),
            canonical = true,
        )
        aliceStore.save(gen1)
        aliceStore.save(gen2)
        aliceStore.save(active)

        maintainPeerSessions(
            sessionStore = aliceStore,
            sessionConfig = config,
            peerDeviceId = bobPeer.device.deviceId,
            nowEpochSeconds = 100_000L,
        )

        val remaining = aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 1)
        assertEquals(2, remaining.size)
        assertNull(remaining.singleOrNull { it.meta.sessionGeneration == 1 })
        assertNotNull(remaining.singleOrNull { it.meta.sessionGeneration == 2 })
        assertNotNull(remaining.singleOrNull { it.meta.sessionGeneration == 3 })
    }

    @Test
    fun housekeeping_prunesStalePendingEpoch2Session() = runTest {
        val testTime = FixedEpochSecondsProvider(300_000L)
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val config = CryptoSessionConfig(
            pendingEpoch2RetentionSeconds = 60,
            canonicalIdleSupersedeSeconds = 60 * 60 * 24,
            supersededRetentionSeconds = 60 * 60,
        )
        val alice = managerForPeer(
            crypto = crypto,
            local = alicePeer,
            peer = bobPeer,
            sessionStore = aliceStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            timeProvider = testTime,
        )
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = MapBackedCryptoSessionStore(),
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
            timeProvider = testTime,
        )

        establishEpoch1Initiator(alice, bob, alicePeer, bobPeer)
        deliverFirstOpkOfferToAlice(alice, bob, alicePeer, bobPeer)

        val pending = aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 2).single()
        assertEquals(SessionStatus.PENDING, pending.meta.status)
        aliceStore.save(
            pending.copy(
                meta = pending.meta.copy(
                    createdAtEpochSeconds = 0L,
                    updatedAtEpochSeconds = 0L,
                ),
            ),
        )

        cryptoHousekeepingFor(
            sessionStore = aliceStore,
            opkRepository = InMemoryOpkRepository(crypto),
            sessionConfig = config,
            timeProvider = testTime,
        ).run(nowEpochSeconds = 300_000L)

        assertEquals(0, aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 2).size)
        assertNotNull(aliceStore.loadActiveCanonical(bobPeer.device.deviceId, sessionEpoch = 1))

        deliverFirstOpkOfferToAlice(alice, bob, alicePeer, bobPeer)
        assertEquals(
            SessionStatus.PENDING,
            aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 2).single().meta.status,
        )
    }

    @Test
    fun housekeeping_prunesExpiredOfferedOpkAndClearsSessionMeta() = runTest {
        val testTime = FixedEpochSecondsProvider(200_000L)
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOpkRepository(crypto, timeProvider = testTime)
        val config = CryptoSessionConfig(
            offeredOpkRetentionSeconds = 60,
            canonicalIdleSupersedeSeconds = 60 * 60 * 24,
            supersededRetentionSeconds = 60 * 60,
        )
        val alice = managerForPeer(
            crypto = crypto,
            local = alicePeer,
            peer = bobPeer,
            sessionStore = MapBackedCryptoSessionStore(),
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
        )
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = bobOpkStore,
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
            sessionConfig = config,
            timeProvider = testTime,
        )

        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )
        bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2))

        val opkId = bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)!!.meta.offeredOpkId!!
        assertEquals(OpkStatus.OFFERED, bobOpkStore.status(opkId))

        cryptoHousekeepingFor(
            sessionStore = bobStore,
            opkRepository = bobOpkStore,
            sessionConfig = config,
            timeProvider = testTime,
        ).run(nowEpochSeconds = 200_061L)

        assertEquals(null, bobOpkStore.status(opkId))
        assertEquals(
            null,
            bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)!!.meta.offeredOpkId,
        )
    }

    @Test
    fun epoch2_encryptDeferredUntilNextInboundAfterOffer() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val alice = managerForPeer(crypto, alicePeer, bobPeer, aliceStore, InMemoryOpkRepository(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = MapBackedCryptoSessionStore(),
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )

        establishEpoch1Initiator(alice, bob, alicePeer, bobPeer)
        deliverFirstOpkOfferToAlice(alice, bob, alicePeer, bobPeer)

        val stillEpoch1 = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(10))
        assertEquals(1, stillEpoch1.sessionEpoch)
        assertEquals(
            SessionStatus.PENDING,
            aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 2).single().meta.status,
        )
    }

    @Test
    fun epoch2_skipsOpkOfferWhenOpkUnavailable() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = FailingAllocateOpkRepository(),
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )
        val aliceStore = MapBackedCryptoSessionStore()
        val alice = managerForPeer(crypto, alicePeer, bobPeer, aliceStore, InMemoryOpkRepository(crypto))

        establishEpoch1Initiator(alice, bob, alicePeer, bobPeer)
        val reply = bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2))

        assertNull(bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)!!.meta.offeredOpkId)
        assertContentEquals(byteArrayOf(2), alice.decryptMessage(bobPeer.device.deviceId, reply))
        assertEquals(0, aliceStore.loadSessions(bobPeer.device.deviceId, sessionEpoch = 2).size)
    }

    @Test
    fun epoch2_bootstrapFailsSoft_missingOfferedOpk_staysOnEpoch1() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val bobStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOpkRepository(crypto)
        val alice =
            managerForPeer(crypto, alicePeer, bobPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = bobOpkStore,
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )

        establishEpoch1Initiator(alice, bob, alicePeer, bobPeer)
        deliverFirstOpkOfferToAlice(alice, bob, alicePeer, bobPeer)
        promoteAliceEpoch2Encrypt(alice, bob, alicePeer, bobPeer)

        val epoch2Frame = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(3))
        val epoch1 = bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1)!!
        bobStore.save(epoch1.copy(meta = epoch1.meta.copy(offeredOpkId = null)))

        assertFailsWith<CryptoSessionException.NoSession> {
            bob.decryptMessage(alicePeer.device.deviceId, epoch2Frame)
        }
        assertNotNull(bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 1))
        assertNull(bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 2))
    }

    @Test
    fun epoch2_earlyEpoch2SendBeforePromote_recoversOnEpoch1() = runTest {
        val alicePeer = buildTestPeerIdentity(crypto, "alice")
        val bobPeer = buildTestPeerIdentity(crypto, "bob")
        val aliceStore = MapBackedCryptoSessionStore()
        val bobStore = MapBackedCryptoSessionStore()
        val bobOpkStore = InMemoryOpkRepository(crypto)
        val alice = managerForPeer(crypto, alicePeer, bobPeer, aliceStore, InMemoryOpkRepository(crypto))
        val bob = managerForPeer(
            crypto = crypto,
            local = bobPeer,
            peer = alicePeer,
            sessionStore = bobStore,
            oneTimePreKeyStore = bobOpkStore,
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )

        establishEpoch1Initiator(alice, bob, alicePeer, bobPeer)
        deliverFirstOpkOfferToAlice(alice, bob, alicePeer, bobPeer)

        val epoch1WhilePending = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(10))
        assertEquals(1, epoch1WhilePending.sessionEpoch)
        assertContentEquals(byteArrayOf(10), bob.decryptMessage(alicePeer.device.deviceId, epoch1WhilePending))

        promoteAliceEpoch2Encrypt(alice, bob, alicePeer, bobPeer)
        val epoch2Frame = alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(11))
        assertEquals(2, epoch2Frame.sessionEpoch)
        assertContentEquals(byteArrayOf(11), bob.decryptMessage(alicePeer.device.deviceId, epoch2Frame))
        assertNotNull(bobStore.loadActiveCanonical(alicePeer.device.deviceId, sessionEpoch = 2))
    }

    private suspend fun establishEpoch1Initiator(
        alice: DefaultCryptoSessionManager,
        bob: DefaultCryptoSessionManager,
        alicePeer: TestPeerIdentity,
        bobPeer: TestPeerIdentity,
    ) {
        bob.decryptMessage(
            alicePeer.device.deviceId,
            alice.encryptMessage(bobPeer.device.deviceId, byteArrayOf(1)),
        )
    }

    private suspend fun deliverFirstOpkOfferToAlice(
        alice: DefaultCryptoSessionManager,
        bob: DefaultCryptoSessionManager,
        alicePeer: TestPeerIdentity,
        bobPeer: TestPeerIdentity,
    ) {
        alice.decryptMessage(
            bobPeer.device.deviceId,
            bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(2)),
        )
    }

    private suspend fun promoteAliceEpoch2Encrypt(
        alice: DefaultCryptoSessionManager,
        bob: DefaultCryptoSessionManager,
        alicePeer: TestPeerIdentity,
        bobPeer: TestPeerIdentity,
    ) {
        alice.decryptMessage(
            bobPeer.device.deviceId,
            bob.encryptMessage(alicePeer.device.deviceId, byteArrayOf(50)),
        )
    }

    private fun sampleRatchetState(): RatchetSessionState =
        RatchetSessionState(
            rootKey = byteArrayOf(0x10),
            sendChainKey = byteArrayOf(0x20),
            recvChainKey = null,
            sendMessageNumber = 1,
            recvMessageNumber = 0,
            previousSendChainLength = 0,
            localDhPrivateKey = byteArrayOf(0x30),
            localDhPublicKey = byteArrayOf(0x31),
            remoteDhPublicKey = byteArrayOf(0x32),
            skippedMessageKeys = emptyMap(),
        )
}
