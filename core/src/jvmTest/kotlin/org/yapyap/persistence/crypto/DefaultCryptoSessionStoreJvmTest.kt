package org.yapyap.persistence.crypto

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.*
import org.yapyap.persistence.db.*
import org.yapyap.protocol.PeerId
import kotlin.test.*

class DefaultCryptoSessionStoreJvmTest {

    private var connection: DatabaseConnection? = null

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    @Test
    fun save_load_list_latestEpoch_markSuperseded_roundTrip() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        seedPeerDevice(db, FixtureAccountId, FixtureRemotePeerId)

        val store = DefaultCryptoSessionStore(db)
        val peer = FixtureRemotePeerId
        val epoch1 = sampleRecord(peer, sessionEpoch = 1, status = SessionStatus.ACTIVE)
        val epoch2 = sampleRecord(peer, sessionEpoch = 2, status = SessionStatus.ACTIVE)

        store.save(epoch1)
        store.save(epoch2)

        assertNull(store.loadActiveCanonical(peer, sessionEpoch = 3))
        assertRecordEquals(epoch1, store.loadActiveCanonical(peer, sessionEpoch = 1)!!)
        assertRecordEquals(epoch2, store.loadActiveCanonical(peer, sessionEpoch = 2)!!)
        assertEquals(2, store.latestEncryptEpoch(peer))
        assertEquals(2, store.listByPeer(peer).size)
        assertRecordEquals(epoch1, store.listByPeer(peer)[0])
        assertRecordEquals(epoch2, store.listByPeer(peer)[1])

        store.markEpochSuperseded(peer, sessionEpoch = 1, updatedAtEpochSeconds = 3_000L)
        assertNull(store.loadActiveCanonical(peer, sessionEpoch = 1))
        val superseded = store.loadSessions(peer, sessionEpoch = 1).single()
        assertEquals(SessionStatus.SUPERSEDED, superseded.meta.status)
        assertEquals(3_000L, superseded.meta.updatedAtEpochSeconds)
        assertEquals(SessionStatus.ACTIVE, store.loadActiveCanonical(peer, sessionEpoch = 2)!!.meta.status)
    }

    @Test
    fun latestEncryptEpoch_ignoresPendingEpoch2() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        seedPeerDevice(db, FixtureAccountId, FixtureRemotePeerId)

        val store = DefaultCryptoSessionStore(db)
        val peer = FixtureRemotePeerId
        store.save(sampleRecord(peer, sessionEpoch = 1, status = SessionStatus.ACTIVE))
        store.save(sampleRecord(peer, sessionEpoch = 2, status = SessionStatus.PENDING))

        assertEquals(1, store.latestEncryptEpoch(peer))
        assertNull(store.loadActiveCanonical(peer, sessionEpoch = 2))
    }

    @Test
    fun save_dualRoleSessions_loadActiveCanonical_setCanonical() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        seedPeerDevice(db, FixtureAccountId, FixtureRemotePeerId)

        val store = DefaultCryptoSessionStore(db)
        val peer = FixtureRemotePeerId
        val initiator = sampleRecord(peer, sessionEpoch = 1, status = SessionStatus.ACTIVE, role = SessionRole.INITIATOR, canonical = true)
        val responder = sampleRecord(peer, sessionEpoch = 1, status = SessionStatus.ACTIVE, role = SessionRole.RESPONDER, canonical = false)

        store.save(initiator)
        store.save(responder)

        assertEquals(2, store.loadSessions(peer, sessionEpoch = 1).size)
        assertEquals(SessionRole.INITIATOR, store.loadActiveCanonical(peer, sessionEpoch = 1)!!.meta.role)

        store.setCanonical(peer, sessionEpoch = 1, SessionRole.INITIATOR, sessionGeneration = 1, canonical = false)
        store.setCanonical(peer, sessionEpoch = 1, SessionRole.RESPONDER, sessionGeneration = 1, canonical = true)
        assertEquals(SessionRole.RESPONDER, store.loadActiveCanonical(peer, sessionEpoch = 1)!!.meta.role)
        assertFalse(store.loadSessions(peer, sessionEpoch = 1).single { it.meta.role == SessionRole.INITIATOR }.canonical)
        assertTrue(store.loadSessions(peer, sessionEpoch = 1).single { it.meta.role == SessionRole.RESPONDER }.canonical)
    }

    @Test
    fun save_loneNonCanonicalSession_promotedToCanonical() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        seedPeerDevice(db, FixtureAccountId, FixtureRemotePeerId)

        val store = DefaultCryptoSessionStore(db)
        val peer = FixtureRemotePeerId
        val loneResponder = sampleRecord(
            peer,
            sessionEpoch = 1,
            status = SessionStatus.ACTIVE,
            role = SessionRole.RESPONDER,
            canonical = false,
        )

        store.save(loneResponder)

        val canonical = store.loadActiveCanonical(peer, sessionEpoch = 1)
        assertNotNull(canonical)
        assertTrue(canonical.canonical)
        assertEquals(SessionRole.RESPONDER, canonical.meta.role)
    }

    @Test
    fun save_dualCanonicalActiveSessions_keepsSingleCanonical() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        seedPeerDevice(db, FixtureAccountId, FixtureRemotePeerId)

        val store = DefaultCryptoSessionStore(db)
        val peer = FixtureRemotePeerId
        val initiator = sampleRecord(peer, sessionEpoch = 1, status = SessionStatus.ACTIVE, role = SessionRole.INITIATOR, canonical = true)
        val responder = sampleRecord(peer, sessionEpoch = 1, status = SessionStatus.ACTIVE, role = SessionRole.RESPONDER, canonical = true)

        store.save(initiator)
        store.save(responder)

        val canonicalCount = store.loadSessions(peer, sessionEpoch = 1).count { it.canonical }
        assertEquals(1, canonicalCount)
        assertEquals(SessionRole.RESPONDER, store.loadActiveCanonical(peer, sessionEpoch = 1)!!.meta.role)
    }

    @Test
    fun markSuperseded_byRole_leavesOtherRoleActive() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        seedPeerDevice(db, FixtureAccountId, FixtureRemotePeerId)

        val store = DefaultCryptoSessionStore(db)
        val peer = FixtureRemotePeerId
        val initiator = sampleRecord(peer, sessionEpoch = 1, status = SessionStatus.ACTIVE, role = SessionRole.INITIATOR, canonical = true)
        val responder = sampleRecord(peer, sessionEpoch = 1, status = SessionStatus.ACTIVE, role = SessionRole.RESPONDER, canonical = false)
        store.save(initiator)
        store.save(responder)

        store.markSuperseded(
            peer,
            sessionEpoch = 1,
            role = SessionRole.INITIATOR,
            sessionGeneration = 1,
            updatedAtEpochSeconds = 2_000L,
        )

        assertEquals(SessionStatus.SUPERSEDED, store.loadSessions(peer, sessionEpoch = 1).single { it.meta.role == SessionRole.INITIATOR }.meta.status)
        assertEquals(SessionStatus.ACTIVE, store.loadSessions(peer, sessionEpoch = 1).single { it.meta.role == SessionRole.RESPONDER }.meta.status)
        assertNull(store.loadActiveCanonical(peer, sessionEpoch = 1))
    }

    @Test
    fun ratchetSkippedKeysCodec_roundTrip() {
        val skipped = mapOf(
            RatchetSkippedKeyId(byteArrayOf(0x01, 0x02), messageNumber = 3) to byteArrayOf(0xAA.toByte()),
            RatchetSkippedKeyId.supersededChain(byteArrayOf(0x03, 0x04, 0x05)) to ByteArray(0),
        )
        val encoded = RatchetSkippedKeysCodec.encode(skipped)
        val decoded = RatchetSkippedKeysCodec.decode(encoded)
        assertEquals(skipped.size, decoded.size)
        for ((key, value) in skipped) {
            val loaded = decoded.entries.first { (k, _) -> k == key }.value
            assertContentEquals(value, loaded)
        }
        val roundTrippedEmpty = RatchetSkippedKeysCodec.decode(RatchetSkippedKeysCodec.encode(emptyMap()))
        assertEquals(0, roundTrippedEmpty.size)
    }

    private fun assertRecordEquals(expected: CryptoSessionRecord, actual: CryptoSessionRecord) {
        assertEquals(expected.peerDeviceId, actual.peerDeviceId)
        assertEquals(expected.sessionEpoch, actual.sessionEpoch)
        val expectedRatchet = expected.ratchetState
        val actualRatchet = actual.ratchetState
        assertContentEquals(expectedRatchet.rootKey, actualRatchet.rootKey)
        assertContentEquals(expectedRatchet.sendChainKey, actualRatchet.sendChainKey)
        assertContentEquals(expectedRatchet.recvChainKey, actualRatchet.recvChainKey)
        assertEquals(expectedRatchet.sendMessageNumber, actualRatchet.sendMessageNumber)
        assertEquals(expectedRatchet.recvMessageNumber, actualRatchet.recvMessageNumber)
        assertEquals(expectedRatchet.previousSendChainLength, actualRatchet.previousSendChainLength)
        assertContentEquals(expectedRatchet.localDhPrivateKey, actualRatchet.localDhPrivateKey)
        assertContentEquals(expectedRatchet.localDhPublicKey, actualRatchet.localDhPublicKey)
        assertContentEquals(expectedRatchet.remoteDhPublicKey, actualRatchet.remoteDhPublicKey)
        assertEquals(expectedRatchet.skippedMessageKeys.size, actualRatchet.skippedMessageKeys.size)
        for ((key, value) in expectedRatchet.skippedMessageKeys) {
            val loaded = actualRatchet.skippedMessageKeys.entries.first { (k, _) -> k == key }.value
            assertContentEquals(value, loaded)
        }
        val expectedMeta = expected.meta
        val actualMeta = actual.meta
        assertEquals(expectedMeta.role, actualMeta.role)
        assertEquals(expectedMeta.x3dhMode, actualMeta.x3dhMode)
        assertEquals(expectedMeta.handshakeSpkId, actualMeta.handshakeSpkId)
        assertEquals(expectedMeta.handshakeOpkId, actualMeta.handshakeOpkId)
        assertContentEquals(expectedMeta.initiatorEphemeralPrivateKey, actualMeta.initiatorEphemeralPrivateKey)
        assertContentEquals(expectedMeta.initiatorEphemeralPublicKey, actualMeta.initiatorEphemeralPublicKey)
        assertEquals(expectedMeta.offeredOpkId, actualMeta.offeredOpkId)
        assertEquals(expectedMeta.status, actualMeta.status)
        assertEquals(expectedMeta.createdAtEpochSeconds, actualMeta.createdAtEpochSeconds)
        assertEquals(expectedMeta.sessionGeneration, actualMeta.sessionGeneration)
        assertEquals(expectedMeta.updatedAtEpochSeconds, actualMeta.updatedAtEpochSeconds)
        assertEquals(expected.canonical, actual.canonical)
    }

    private fun sampleRecord(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        status: SessionStatus,
        role: SessionRole = if (sessionEpoch == 1) SessionRole.INITIATOR else SessionRole.RESPONDER,
        canonical: Boolean = true,
    ): CryptoSessionRecord =
        CryptoSessionRecord(
            peerDeviceId = peerDeviceId,
            sessionEpoch = sessionEpoch,
            canonical = canonical,
            ratchetState = RatchetSessionState(
                rootKey = byteArrayOf(0x10, 0x11),
                sendChainKey = byteArrayOf(0x20),
                recvChainKey = null,
                sendMessageNumber = sessionEpoch,
                recvMessageNumber = sessionEpoch + 1,
                previousSendChainLength = 0,
                localDhPrivateKey = byteArrayOf(0x30),
                localDhPublicKey = byteArrayOf(0x31),
                remoteDhPublicKey = byteArrayOf(0x32),
                skippedMessageKeys = mapOf(
                    RatchetSkippedKeyId(byteArrayOf(0x40), messageNumber = 1) to byteArrayOf(0x50),
                ),
            ),
            meta = CryptoSessionMeta(
                role = role,
                x3dhMode = if (sessionEpoch == 1) X3dhMode.THREE_DH else X3dhMode.FOUR_DH,
                handshakeSpkId = "spk-fixture",
                handshakeOpkId = if (sessionEpoch == 2) "opk-fixture" else null,
                initiatorEphemeralPrivateKey = byteArrayOf(0x60),
                initiatorEphemeralPublicKey = byteArrayOf(0x61),
                offeredOpkId = if (sessionEpoch == 1) "opk-offered" else null,
                status = status,
                createdAtEpochSeconds = 1_000L + sessionEpoch,
                updatedAtEpochSeconds = 2_000L + sessionEpoch,
            ),
        )
}
