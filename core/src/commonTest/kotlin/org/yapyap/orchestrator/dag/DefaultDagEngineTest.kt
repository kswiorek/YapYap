package org.yapyap.orchestrator.dag

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.persistence.db.MessageLifecycleState
import org.yapyap.persistence.messaging.CausalHoldRepository
import org.yapyap.persistence.messaging.CausalHoldRow
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.MessageRow
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.time.EpochSecondsProvider
import kotlin.test.*

/**
 * Pure-Kotlin contract tests for [DefaultDagEngine] backed by fake in-memory repositories.
 * Safe to move to commonTest.
 */
class DefaultDagEngineTest {

    private lateinit var dagEngine: DefaultDagEngine
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var causalHoldRepo: FakeCausalHoldRepository
    private lateinit var identityResolver: FakeIdentityResolver
    private lateinit var signatureProvider: FakeSignatureProvider
    private lateinit var timeProvider: MutableEpochSecondsProvider

    private val testAccount = AccountId("dag-sender-account")
    private val remoteAccount = AccountId("dag-remote-account")
    private val testDeviceId = PeerId("test-device-id")
    private val remoteDeviceId = PeerId("remote-device-id")
    private val roomId = "dag-test-room"

    @BeforeTest
    fun setup() {
        messageRepo = FakeMessageRepository()
        causalHoldRepo = FakeCausalHoldRepository(messageRepo)
        identityResolver = FakeIdentityResolver(testAccount, testDeviceId)
        signatureProvider = FakeSignatureProvider()
        timeProvider = MutableEpochSecondsProvider(1_000_000L)
        dagEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            identityResolver = identityResolver,
            signatureProvider = signatureProvider,
            timeProvider = timeProvider,
        )
    }

    @Test
    fun append_emptyRoom_assignsLamportZeroAndNullPrevId() = runTest {
        val payload = dagEngine.append(roomId, MessageDraft.Text("first"))

        assertEquals(0L, payload.lamportClock)
        assertNull(payload.prevId)
        assertEquals(roomId, payload.roomId)
        assertEquals(testAccount.id, payload.senderAccountId)
        assertEquals("first", (payload as MessagePayload.Text).text)
        assertEquals(timeProvider.nowEpochSeconds(), payload.createdAtEpochSeconds)
        assertFalse(messageRepo.findById(payload.messageId)!!.isOrphaned)
    }

    @Test
    fun append_chainsOffRoomTail_incrementsLamport() = runTest {
        val first = dagEngine.append(roomId, MessageDraft.Text("first"))
        timeProvider.t += 1L
        val second = dagEngine.append(roomId, MessageDraft.Text("second"))

        assertEquals(1L, second.lamportClock)
        assertEquals(first.messageId, second.prevId)
    }

    @Test
    fun append_concurrentMessagesFromSameSender_haveMonotonicLamportAndChain() = runTest {
        val a = dagEngine.append(roomId, MessageDraft.Text("a"))
        val b = dagEngine.append(roomId, MessageDraft.Text("b"))
        val c = dagEngine.append(roomId, MessageDraft.Text("c"))

        assertEquals(0L, a.lamportClock)
        assertEquals(1L, b.lamportClock)
        assertEquals(2L, c.lamportClock)
        assertEquals(a.messageId, b.prevId)
        assertEquals(b.messageId, c.prevId)
    }

    @Test
    fun ingest_newMessageWithExistingPrev_returnsInserted_noGaps() = runTest {
        val first = dagEngine.append(roomId, MessageDraft.Text("first"))

        val remotePayload = MessagePayload.Text(
            messageId = "remote-msg-1",
            roomId = roomId,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = first.messageId,
            lamportClock = 1L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "from remote",
        )

        val result = dagEngine.ingest(remotePayload)

        assertTrue(result is IngestResult.Inserted)
        assertEquals(remotePayload, result.payload)
        assertTrue(result.closedGapMissingPrevIds.isEmpty())
        assertFalse(messageRepo.findById(remotePayload.messageId)!!.isOrphaned)
    }

    @Test
    fun ingest_duplicateMessage_isDeduped_noNewRow() = runTest {
        val first = dagEngine.append(roomId, MessageDraft.Text("first"))
        assertEquals(1, messageRepo.byId.size)

        // Ingesting an appended message is a dedup case.
        val result = dagEngine.ingest(first)
        assertNull(result)
        // No new row inserted.
        assertEquals(1, messageRepo.byId.size)
    }

    @Test
    fun ingest_missingPrev_returnsBecameOrphan_andCreatesGap() = runTest {
        val remotePayload = MessagePayload.Text(
            messageId = "orphan-msg-1",
            roomId = roomId,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = "nonexistent-prev",
            lamportClock = 5L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "i am orphaned",
        )

        val result = dagEngine.ingest(remotePayload)

        assertTrue(result is IngestResult.BecameOrphan)
        assertEquals("nonexistent-prev", result.missingPrevId)
        assertEquals(emptyList<String>(), result.closedGapMissingPrevIds)
        assertTrue(messageRepo.findById(remotePayload.messageId)!!.isOrphaned)

        val gaps = dagEngine.openGaps(roomId)
        assertEquals(1, gaps.size)
        assertEquals("nonexistent-prev", gaps[0].missingPrevId)
        assertEquals("orphan-msg-1", gaps[0].orphanedMessageId)
    }

    @Test
    fun ingest_closesGapWhenMissingMessageArrives() = runTest {
        // 1. Ingest an orphan that references a missing prev.
        val orphan = MessagePayload.Text(
            messageId = "orphan-msg-1",
            roomId = roomId,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = "missing-prev-id",
            lamportClock = 5L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "waiting for prev",
        )
        assertTrue(dagEngine.ingest(orphan) is IngestResult.BecameOrphan)
        assertEquals(1, dagEngine.openGaps(roomId).size)
        assertTrue(messageRepo.findById(orphan.messageId)!!.isOrphaned)

        // 2. Ingest the previously-missing message (prevId = null → not orphaned).
        val missing = MessagePayload.Text(
            messageId = "missing-prev-id",
            roomId = roomId,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = null,
            lamportClock = 4L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "i am the prev",
        )
        val missingResult = dagEngine.ingest(missing)

        // 3. The arrived message is Inserted; it closed the gap pointing at it.
        assertTrue(missingResult is IngestResult.Inserted)
        assertEquals(1, missingResult.closedGapMissingPrevIds.size)
        assertEquals("missing-prev-id", missingResult.closedGapMissingPrevIds[0])

        // Gap closed; orphan no longer flagged.
        assertEquals(0, dagEngine.openGaps(roomId).size)
        assertFalse(messageRepo.findById(orphan.messageId)!!.isOrphaned)
    }

    @Test
    fun ingest_closesMultipleGapsWaitingOnSameMessage() = runTest {
        // Two orphans, both waiting for "missing-prev-id".
        val orphan1 = MessagePayload.Text(
            messageId = "orphan-1",
            roomId = roomId,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = "missing-prev-id",
            lamportClock = 5L,
            createdAtEpochSeconds = 10L,
            text = "a",
        )
        val orphan2 = MessagePayload.Text(
            messageId = "orphan-2",
            roomId = roomId,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = "missing-prev-id",
            lamportClock = 6L,
            createdAtEpochSeconds = 11L,
            text = "b",
        )
        dagEngine.ingest(orphan1)
        dagEngine.ingest(orphan2)
        assertEquals(2, dagEngine.openGaps(roomId).size)

        val missing = MessagePayload.Text(
            messageId = "missing-prev-id",
            roomId = roomId,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = null,
            lamportClock = 4L,
            createdAtEpochSeconds = 9L,
            text = "the prev",
        )
        val result = dagEngine.ingest(missing)

        assertTrue(result is IngestResult.Inserted)
        assertEquals(2, result.closedGapMissingPrevIds.size)
        assertTrue(result.closedGapMissingPrevIds.all { it == "missing-prev-id" })

        assertEquals(0, dagEngine.openGaps(roomId).size)
        assertFalse(messageRepo.findById(orphan1.messageId)!!.isOrphaned)
        assertFalse(messageRepo.findById(orphan2.messageId)!!.isOrphaned)
    }

    @Test
    fun getMessagesInRoom_paginated_withCursor() = runTest {
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        val m2 = dagEngine.append(roomId, MessageDraft.Text("b"))
        val m3 = dagEngine.append(roomId, MessageDraft.Text("c"))

        // First page of 2 (newest first).
        val page1 = dagEngine.getMessagesInRoom(roomId, limit = 2)
        assertEquals(2, page1.size)
        assertEquals(m3.messageId, page1[0].messageId)
        assertEquals(m2.messageId, page1[1].messageId)

        // Cursor = oldest row in page1.
        val cursor = MessagePageCursor(
            createdAtEpochSeconds = page1[1].createdAtEpochSeconds,
            lamportClock = page1[1].lamportClock,
            messageId = page1[1].messageId,
        )
        val page2 = dagEngine.getMessagesInRoom(roomId, limit = 2, before = cursor)
        assertEquals(1, page2.size)
        assertEquals(m1.messageId, page2[0].messageId)
    }

    @Test
    fun getMessagesInRoom_emptyPagination_returnsEmpty() = runTest {
        val result = dagEngine.getMessagesInRoom(roomId, limit = 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun ancestorsOf_walksPrevIdChain() = runTest {
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        val m2 = dagEngine.append(roomId, MessageDraft.Text("b"))
        val m3 = dagEngine.append(roomId, MessageDraft.Text("c"))

        val ancestors = dagEngine.ancestorsOf(roomId, m3.messageId, limit = 10)

        assertEquals(2, ancestors.size)
        assertEquals(m2.messageId, ancestors[0].messageId)
        assertEquals(m1.messageId, ancestors[1].messageId)
    }

    @Test
    fun ancestorsOf_returnsEmptyForRootMessage() = runTest {
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        val ancestors = dagEngine.ancestorsOf(roomId, m1.messageId, limit = 10)
        assertTrue(ancestors.isEmpty())
    }

    @Test
    fun ancestorsOf_stopsAtLimit() = runTest {
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        dagEngine.append(roomId, MessageDraft.Text("b"))
        val m3 = dagEngine.append(roomId, MessageDraft.Text("c"))

        val ancestors = dagEngine.ancestorsOf(roomId, m3.messageId, limit = 1)
        assertEquals(1, ancestors.size)
    }

    @Test
    fun openGaps_byRoom_filtersToRoom() = runTest {
        // Orphan in roomId.
        val orphan1 = MessagePayload.Text(
            messageId = "orphan-1",
            roomId = roomId,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = "missing-a",
            lamportClock = 1L,
            createdAtEpochSeconds = 0L,
            text = "x",
        )
        dagEngine.ingest(orphan1)

        // Orphan in a second room.
        val otherRoom = "other-room"
        val orphan2 = MessagePayload.Text(
            messageId = "orphan-2",
            roomId = otherRoom,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = "missing-b",
            lamportClock = 1L,
            createdAtEpochSeconds = 0L,
            text = "y",
        )
        dagEngine.ingest(orphan2)

        val gapsInRoom = dagEngine.openGaps(roomId)
        assertEquals(1, gapsInRoom.size)
        assertEquals("orphan-1", gapsInRoom[0].orphanedMessageId)

        val gapsInOther = dagEngine.openGaps(otherRoom)
        assertEquals(1, gapsInOther.size)
        assertEquals("orphan-2", gapsInOther[0].orphanedMessageId)

        val allGaps = dagEngine.openGaps()
        assertEquals(2, allGaps.size)
    }

    @Test
    fun append_globalEventDraft_buildsGlobalEventPayload() = runTest {
        val payload = dagEngine.append(roomId, MessageDraft.GlobalEvent(byteArrayOf(0x01, 0x02)))

        assertTrue(payload is MessagePayload.GlobalEvent)
        assertEquals(roomId, payload.roomId)
        assertContentEquals(byteArrayOf(0x01, 0x02), payload.eventBytes)
        assertEquals(0L, payload.lamportClock)
    }

    @Test
    fun ingest_invalidSignature_returnsNull_doesNotInsert() = runTest {
        val remotePayload = MessagePayload.Text(
            messageId = "invalid-sig-msg",
            roomId = roomId,
            senderAccountId = remoteAccount.id,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = null,
            lamportClock = 1L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "should be rejected",
        )

        // Replace with a rejecting signature provider
        val rejectingEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            identityResolver = identityResolver,
            signatureProvider = FakeRejectingSignatureProvider(),
            timeProvider = timeProvider,
        )

        val result = rejectingEngine.ingest(remotePayload)

        assertNull(result)
        assertNull(messageRepo.findById("invalid-sig-msg"))
    }

    @Test
    fun append_setsAuthorDeviceIdAndSignature() = runTest {
        val payload = dagEngine.append(roomId, MessageDraft.Text("hello"))

        assertEquals(testDeviceId, payload.authorDeviceId)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), payload.authorSignature)
    }
}

/* ---------- fake repos + helpers (pure Kotlin, commonTest-safe) ---------- */

private class MutableEpochSecondsProvider(var t: Long) : EpochSecondsProvider {
    override fun nowEpochSeconds(): Long = t
}

private class FakeMessageRepository : MessageRepository {
    val byId = mutableMapOf<String, MessageRow>()

    override fun insert(
        payload: MessagePayload,
        lifecycleState: MessageLifecycleState,
        isOrphaned: Boolean,
    ): Boolean {
        if (byId.containsKey(payload.messageId)) {
            // INSERT OR IGNORE semantics — duplicated key is a no-op.
            val existing = byId[payload.messageId]!!
            return existing == MessageRow(payload, lifecycleState, isOrphaned)
        }
        byId[payload.messageId] = MessageRow(payload, lifecycleState, isOrphaned)
        return true
    }

    override fun findById(messageId: String): MessageRow? = byId[messageId]

    override fun findRoomTail(roomId: String): MessageRow? =
        byId.values
            .filter { it.payload.roomId == roomId }
            .maxWithOrNull(
                compareBy<MessageRow> { it.payload.lamportClock }
                    .thenBy { it.payload.createdAtEpochSeconds }
                    .thenBy { it.payload.messageId }
            )

    override fun findMessagesInRoomPageDesc(
        roomId: String,
        limit: Int,
        cursorCreated: Long?,
        cursorLamport: Long,
        cursorMessageId: String,
    ): List<MessageRow> {
        val all = byId.values
            .filter { it.payload.roomId == roomId }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAtEpochSeconds }
                    .thenByDescending { it.payload.lamportClock }
                    .thenByDescending { it.payload.messageId }
            )
        val filtered = if (cursorCreated == null) {
            all
        } else {
            // Strictly older than the cursor row (all three key sub-comparisons).
            all.filter { row ->
                val rowCreated = row.payload.createdAtEpochSeconds
                val rowLamport = row.payload.lamportClock
                val rowId = row.payload.messageId
                rowCreated < cursorCreated ||
                    (rowCreated == cursorCreated && rowLamport < cursorLamport) ||
                    (rowCreated == cursorCreated && rowLamport == cursorLamport && rowId < cursorMessageId)
            }
        }
        return filtered.take(limit)
    }

    override fun findAllInRoom(roomId: String): List<MessageRow> =
        byId.values
            .filter { it.payload.roomId == roomId }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAtEpochSeconds }
                    .thenByDescending { it.payload.lamportClock }
                    .thenByDescending { it.payload.messageId }
            )

    override fun maxLamportInRoom(roomId: String): Long? =
        byId.values
            .filter { it.payload.roomId == roomId }
            .maxOfOrNull { it.payload.lamportClock }

    override fun updateOrphanedFlag(messageId: String, isOrphaned: Boolean) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(isOrphaned = isOrphaned)
    }

    override fun updateLifecycleState(messageId: String, state: MessageLifecycleState) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(lifecycleState = state)
    }
}

private class FakeCausalHoldRepository(
    private val messageRepo: FakeMessageRepository,
) : CausalHoldRepository {
    private val rows = mutableListOf<CausalHoldRow>()

    override fun insert(gapId: String, missingPrevId: String, orphanedMessageId: String, detectedTimestamp: Long) {
        rows.add(CausalHoldRow(gapId, missingPrevId, orphanedMessageId, detectedTimestamp))
    }

    override fun findByMissingPrevId(missingPrevId: String): List<CausalHoldRow> =
        rows.filter { it.missingPrevId == missingPrevId }

    override fun findByRoom(roomId: String): List<CausalHoldRow> =
        // Mirror the SQL JOIN: a causal_hold row belongs to the room of its orphaned message.
        rows.filter { row ->
            val orphan = messageRepo.findById(row.orphanedMessageId)
            orphan?.payload?.roomId == roomId
        }

    override fun findAll(): List<CausalHoldRow> = rows.toList()

    override fun deleteByMissingPrevId(missingPrevId: String) {
        rows.removeAll { it.missingPrevId == missingPrevId }
    }

    override fun deleteByOrphanedMessageId(orphanedMessageId: String) {
        rows.removeAll { it.orphanedMessageId == orphanedMessageId }
    }
}

private class FakeIdentityResolver(
    private val localAccountId: AccountId,
    private val localDeviceId: PeerId,
) : IdentityResolver {
    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = error("not used")
    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord = error("not used")
    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = localDeviceId
    override suspend fun getLocalAccountId(): AccountId = localAccountId
    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord = error("not used")
    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint = error("not used")
    override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used")
    override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used")
    override suspend fun resolvePeerX3dhRemoteKeys(deviceId: PeerId, signedPreKeyId: String?) = error("not used")
    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord = error("not used")
    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord = error("not used")
}

private class FakeSignatureProvider : SignatureProvider {
    override suspend fun sign(message: ByteArray): ByteArray = byteArrayOf(0x01, 0x02, 0x03)

    override suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean = true

    override suspend fun verifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray,
    ): Boolean = true
}

private class FakeRejectingSignatureProvider : SignatureProvider {
    override suspend fun sign(message: ByteArray): ByteArray = byteArrayOf(0x01, 0x02, 0x03)

    override suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean = false

    override suspend fun verifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray,
    ): Boolean = false
}