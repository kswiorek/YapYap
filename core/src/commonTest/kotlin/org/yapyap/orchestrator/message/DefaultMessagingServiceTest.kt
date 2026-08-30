package org.yapyap.orchestrator.message

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.yapyap.config.MessageLimits
import org.yapyap.crypto.e2ee.testCryptoLimits
import org.yapyap.crypto.e2ee.testMessageLimits
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.DefaultDagEngine
import org.yapyap.orchestrator.dag.MessageDraft
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.pipeline.DefaultInboundMessagePipeline
import org.yapyap.orchestrator.runtime.message.DefaultMessagingService
import org.yapyap.orchestrator.runtime.message.IncomingMessageEvent
import org.yapyap.orchestrator.runtime.message.MessageDisplayItem
import org.yapyap.persistence.messaging.*
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.*
import org.yapyap.time.FixedEpochProvider
import kotlin.test.*
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Pure-Kotlin contract tests for [org.yapyap.orchestrator.runtime.message.DefaultMessagingService] backed by fake in-memory repos.
 * Safe to move to commonTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMessagingServiceTest {

    private lateinit var dagEngine: DefaultDagEngine
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var causalHoldRepo: FakeCausalHoldRepository
    private lateinit var identityResolver: FakeIdentityResolver
    private lateinit var timeProvider: FixedEpochProvider
    private lateinit var router: RecordingRouter
    private lateinit var roomMembershipRepo: FakeRoomRepository

    private val localAccount = AccountId("msg-local-account")
    private val remoteAccount = AccountId("msg-remote-account")
    private val roomId = RoomId(Uuid.random())

    @BeforeTest
    fun setup() {
        messageRepo = FakeMessageRepository()
        causalHoldRepo = FakeCausalHoldRepository(messageRepo)
        identityResolver = FakeIdentityResolver(localAccount)
        timeProvider = FixedEpochProvider(1_000_000L)
        router = RecordingRouter()
        roomMembershipRepo = FakeRoomRepository(mutableMapOf(roomId to listOf(localAccount, remoteAccount)))
        dagEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            roomRepository = roomMembershipRepo,
            identityResolver = identityResolver,
            timeProvider = timeProvider,
            signatureProvider = FakeSignatureProvider(),
        )
    }

    private fun startStack(
        scope: TestScope,
        pipeline: DefaultInboundMessagePipeline,
        service: DefaultMessagingService,
    ) {
        // Long-running collectors (pipeline + service subscribers) go on backgroundScope so
        // runTest cancels them automatically when the test body completes — otherwise they stay
        // active forever and runTest reports UncompletedCoroutinesError.
        pipeline.start(scope.backgroundScope)
        service.start(scope.backgroundScope)
        scope.advanceUntilIdle()
    }

    private fun newService(
        scope: TestScope,
        pipeline: DefaultInboundMessagePipeline = DefaultInboundMessagePipeline(router, dagEngine),
        messageLimits: MessageLimits = testMessageLimits(),
    ): DefaultMessagingService = DefaultMessagingService(
        dagEngine = dagEngine,
        router = router,
        pipeline = pipeline,
        roomRepository = roomMembershipRepo,
        identityResolver = identityResolver,
        timeProvider = timeProvider,
        messageLimits = MutableStateFlow(messageLimits),
        orchestratorConfig = MutableStateFlow(OrchestratorConfig()),
    )

    @Test
    fun sendTextMessage_appendsToDag_andFansOutToAllMembers() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val result = service.sendTextMessage(roomId, "hello from local")

        assertEquals(SendMessageStatus.SUCCESS, result.status)
        // Sent to all room members — no account-level self-filter (router handles device skip).
        assertEquals(2, router.sentTargets.size)
        assertTrue(router.sentTargets.contains(remoteAccount))
        assertTrue(router.sentTargets.contains(localAccount))

        // DAG contains the message.
        val messages = dagEngine.getMessagesInRoom(roomId)
        assertEquals(1, messages.size)
        assertEquals("hello from local", (messages[0] as MessagePayload.Text).text)
        assertEquals(localAccount, messages[0].senderAccountId)
    }

    @Test
    fun sendTextMessage_toEmptyRoom_returnsSuccessWithZeroPeers() = runTest(UnconfinedTestDispatcher()) {
        roomMembershipRepo.members[roomId] = emptyList()
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val result = service.sendTextMessage(roomId, "no peers here")

        assertEquals(SendMessageStatus.SUCCESS, result.status)
        assertEquals(0, result.peersTotal)
        assertEquals(0, result.peersQueued)
        assertEquals(0, router.sentTargets.size)
    }

    @Test
    fun sendTextMessage_oversizedText_returnsTooLarge_andDoesNotAppendToDag() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(
            this,
            pipeline,
            messageLimits = MessageLimits(
                transport = testTransportLimits(),
                crypto = testCryptoLimits(),
                maxTextMessageBytes = 8,
            ),
        )
        startStack(this, pipeline, service)

        val result = service.sendTextMessage(roomId, "this text is too long")

        assertEquals(SendMessageStatus.FAILURE, result.status)
        assertEquals(SendFailureKind.TOO_LARGE, result.failureKind)
        assertEquals(0, result.peersTotal)
        assertEquals(0, result.peersQueued)
        assertEquals(0, router.sentTargets.size)
        assertEquals(0, dagEngine.getMessagesInRoom(roomId).size)
    }

    @Test
    fun openRoom_loadsInitialPage() = runTest(UnconfinedTestDispatcher()) {
        // Pre-seed two messages in the DAG.
        dagEngine.append(roomId, MessageDraft.Text("msg-1"))
        dagEngine.append(roomId, MessageDraft.Text("msg-2"))

        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val window = service.openRoom(roomId, initialPageSize = 100)
        advanceUntilIdle()

        val items = window.displayItems.value
        assertEquals(2, items.size)
        // Oldest -> newest rendering.
        assertTrue(items[0] is MessageDisplayItem.Text)
        assertEquals("msg-1", (items[0] as MessageDisplayItem.Text).text)
        assertTrue(items[1] is MessageDisplayItem.Text)
        assertEquals("msg-2", (items[1] as MessageDisplayItem.Text).text)
        window.close()
    }

    @Test
    fun sendTextMessage_updatesOpenWindow() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val window = service.openRoom(roomId, initialPageSize = 10)
        advanceUntilIdle()
        assertTrue(window.displayItems.value.isEmpty())

        service.sendTextMessage(roomId, "window update")
        advanceUntilIdle()

        val items = window.displayItems.value
        assertEquals(1, items.size)
        val item = items[0]
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals("window update", item.text)
        assertEquals(localAccount, item.accountId)
        window.close()
    }

    @Test
    fun incomingMessage_updatesOpenWindow_withSenderTimestamp() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val msg1Uuid = Uuid.random()

        val window = service.openRoom(roomId, initialPageSize = 100)
        advanceUntilIdle()

        val remoteTimestamp = 1_000_500L
        val incoming = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = remoteTimestamp,
            text = "hello from remote",
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(incoming)
        advanceUntilIdle()

        val items = window.displayItems.value
        assertEquals(1, items.size)
        val item = items[0]
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals(remoteAccount, item.accountId)
        // Sender's composition timestamp is preserved, not the receiver's time.
        assertEquals(remoteTimestamp, item.timestamp)
    }

    @Test
    fun incomingOutOfOrderMessage_showsGapIndicator() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val msg1Uuid = Uuid.random()
        val prevUuid = Uuid.random()

        val window = service.openRoom(roomId, initialPageSize = 100)
        advanceUntilIdle()

        val orphan = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevId = prevUuid,
            lamportClock = 1L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "i am orphaned",
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(orphan)
        advanceUntilIdle()

        val items = window.displayItems.value
        assertEquals(2, items.size)
        assertTrue(items[0] is MessageDisplayItem.Text)
        assertTrue(items[1] is MessageDisplayItem.Gap)
        assertEquals(prevUuid, (items[1] as MessageDisplayItem.Gap).missingPrevId)
    }

    @Test
    fun gapClosure_removesGapFromWindow() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val msg1Uuid = Uuid.random()
        val prevUuid = Uuid.random()

        val window = service.openRoom(roomId, initialPageSize = 100)
        advanceUntilIdle()

        // Orphan arrives.
        val orphan = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevId = prevUuid,
            lamportClock = 1L,
            createdAtEpochSeconds = 1_000_500L,
            text = "waiting",
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(orphan)
        advanceUntilIdle()

        assertEquals(2, window.displayItems.value.size)
        assertTrue(window.displayItems.value.any { it is MessageDisplayItem.Gap })

        // The previously-missing message arrives.
        val missing = MessagePayload.Text(
            messageId = prevUuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = 1_000_400L,
            text = "i am the prev",
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(missing)
        advanceUntilIdle()

        val items = window.displayItems.value
        assertEquals(2, items.size)
        assertEquals(0, items.count { it is MessageDisplayItem.Gap })
        // Both are text items now.
        assertTrue(items.all { it is MessageDisplayItem.Text })
        // Older first: missing (createdAt=1_000_400) then orphan (1_000_500).
        assertEquals("i am the prev", (items[0] as MessageDisplayItem.Text).text)
        assertEquals("waiting", (items[1] as MessageDisplayItem.Text).text)
    }

    @Test
    fun incomingMessage_emitsMessageEvent_onlyFromOthers() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val msg1Uuid = Uuid.random()
        val msg2Uuid = Uuid.random()

        val received = mutableListOf<IncomingMessageEvent>()
        val collectorJob = backgroundScope.launch { service.incomingMessageEvents.collect { received.add(it) } }
        advanceUntilIdle()

        // From remote → emits.
        val remoteIncoming = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "hi from remote",
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(remoteIncoming)
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(roomId, received[0].roomId)
        assertEquals(remoteAccount, received[0].senderAccountId)
        assertEquals("hi from remote", received[0].messagePreview)

        // From self → no event.
        received.clear()
        val selfIncoming = MessagePayload.Text(
            messageId = msg2Uuid,
            roomId = roomId,
            senderAccountId = localAccount,
            prevId = null,
            lamportClock = 1L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "from me",
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(selfIncoming)
        advanceUntilIdle()
        assertTrue(received.isEmpty())

        collectorJob.cancel()
    }

    @Test
    fun incomingMessage_preview_isTruncatedForLongText() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val msg1Uuid = Uuid.random()

        val received = mutableListOf<IncomingMessageEvent>()
        val collectorJob = backgroundScope.launch { service.incomingMessageEvents.collect { received.add(it) } }
        advanceUntilIdle()

        val longText = "x".repeat(120)
        val remoteIncoming = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = longText,
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(remoteIncoming)
        advanceUntilIdle()

        assertEquals(1, received.size)
        // Preview is padded with ellipsis when text exceeds 80 chars.
        assertEquals(80, received[0].messagePreview.length)
        // Last char is the ellipsis codepoint.
        assertEquals("\u2026", received[0].messagePreview.takeLast(1))

        collectorJob.cancel()
    }

    @Test
    fun globalEvent_doesNotUpdateWindow_andDoesNotEmitEvent() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val window = service.openRoom(roomId, initialPageSize = 100)
        val received = mutableListOf<IncomingMessageEvent>()
        val collectorJob = backgroundScope.launch { service.incomingMessageEvents.collect { received.add(it) } }
        advanceUntilIdle()

        val globalEvent = MessagePayload.GlobalEvent(
            messageId = Uuid.random(),
            senderAccountId = remoteAccount,
            prevId = null,
            lamportClock = 0L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            eventBytes = byteArrayOf(0x01),
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(globalEvent)
        advanceUntilIdle()

        // Window stays empty (GlobalEvent filtered out).
        assertEquals(0, window.displayItems.value.size)
        // Event is not emitted (Text-only branch, else branch is a no-op).
        assertEquals(0, received.size)

        collectorJob.cancel()
    }

    @Test
    fun loadOlder_paginatesBackward() = runTest(UnconfinedTestDispatcher()) {
        // Three messages: m1 (oldest), m2, m3 (newest).
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        timeProvider.advanceBy(1L)
        val m2 = dagEngine.append(roomId, MessageDraft.Text("b"))
        timeProvider.advanceBy(1L)
        val m3 = dagEngine.append(roomId, MessageDraft.Text("c"))

        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val window = service.openRoom(roomId, initialPageSize = 2)
        advanceUntilIdle()

        // Page size 2: shows m2 and m3 (newest), oldest->newest render.
        assertEquals(2, window.displayItems.value.size)
        assertEquals("b", (window.displayItems.value[0] as MessageDisplayItem.Text).text)
        assertEquals("c", (window.displayItems.value[1] as MessageDisplayItem.Text).text)
        assertTrue(window.hasMoreOlder.value)

        val loaded = window.loadOlder(pageSize = 10)
        advanceUntilIdle()

        assertEquals(1, loaded)
        // Now we have all three: m1 (newly prepended), m2, m3.
        assertEquals(3, window.displayItems.value.size)
        assertEquals("a", (window.displayItems.value[0] as MessageDisplayItem.Text).text)
        assertFalse(window.hasMoreOlder.value)
    }
}

/* ---------- fakes (pure Kotlin, commonTest-safe) ---------- */

private class FakeRoomRepository(
    val members: MutableMap<RoomId, List<AccountId>>,
) : RoomRepository {
    override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> = members[roomId] ?: emptyList()

    override suspend fun updateLocalSeq(roomId: RoomId, seqN: Long) = Unit

    override suspend fun getLocalSeq(roomId: RoomId): Long? = null
}

private class FakeMessageRepository : MessageRepository {
    val byId = mutableMapOf<Uuid, MessageRow>()

    override suspend fun insert(
        payload: MessagePayload,
        isOrphaned: Boolean,
    ): Boolean {
        if (byId.containsKey(payload.messageId)) {
            // INSERT OR IGNORE semantics.
            return true
        }
        byId[payload.messageId] = MessageRow(payload, isOrphaned)
        return true
    }

    override suspend fun findById(messageId: Uuid): MessageRow? = byId[messageId]

    override suspend fun findRoomTail(roomId: RoomId): MessageRow? =
        byId.values
            .filter { it.payload.roomId == roomId }
            .maxWithOrNull(
                compareBy<MessageRow> { it.payload.lamportClock }
                    .thenBy { it.payload.createdAtEpochSeconds }
                    .thenBy { it.payload.messageId }
            )

    override suspend fun findMessagesInRoomPageDesc(
        roomId: RoomId,
        limit: Int,
        cursor: MessageCursor?,
    ): List<MessageRow> {
        val all = byId.values
            .filter { it.payload.roomId == roomId }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAtEpochSeconds }
                    .thenByDescending { it.payload.lamportClock }
                    .thenByDescending { it.payload.messageId }
            )
        val filtered = if (cursor == null) {
            all
        } else {
            // Strictly older than the cursor row (all three key sub-comparisons).
            all.filter { row ->
                val rowCreated = row.payload.createdAtEpochSeconds
                val rowLamport = row.payload.lamportClock
                val rowId = row.payload.messageId
                rowCreated < cursor.createdAtEpochSeconds ||
                        (rowCreated == cursor.createdAtEpochSeconds && rowLamport < cursor.lamportClock) ||
                        (rowCreated == cursor.createdAtEpochSeconds && rowLamport == cursor.lamportClock && cursor.messageId.let { rowId < it })
            }
        }
        return filtered.take(limit)
    }

    override suspend fun findAllInRoom(roomId: RoomId): List<MessageRow> =
        byId.values
            .filter { it.payload.roomId == roomId }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAtEpochSeconds }
                    .thenByDescending { it.payload.lamportClock }
                    .thenByDescending { it.payload.messageId }
            )

    override suspend fun maxLamportInRoom(roomId: RoomId): Long? =
        byId.values
            .filter { it.payload.roomId == roomId }
            .maxOfOrNull { it.payload.lamportClock }

    override suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(isOrphaned = isOrphaned)
    }

    override suspend fun isOrphanAtLamport(roomId: RoomId, lamport: Long): Boolean =
        byId.values.any { it.payload.roomId == roomId && it.payload.lamportClock == lamport && it.isOrphaned }

    override suspend fun maxLamportBelow(roomId: RoomId, lamport: Long): Long? =
        byId.values
            .filter { it.payload.roomId == roomId && it.payload.lamportClock < lamport }
            .maxOfOrNull { it.payload.lamportClock }

    override suspend fun findMessagesInLamportRange(
        roomId: RoomId,
        lowerInclusive: Long,
        upperInclusive: Long,
        limit: Int,
    ): List<MessageRow> =
        byId.values
            .filter { it.payload.roomId == roomId }
            .filter { it.payload.lamportClock in lowerInclusive..upperInclusive }
            .sortedWith(
                compareBy<MessageRow> { it.payload.lamportClock }
                    .thenBy { it.payload.createdAtEpochSeconds }
                    .thenBy { it.payload.messageId }
            )
            .take(limit)

    override suspend fun countAtLamport(roomId: RoomId, lamport: Long): Long =
        byId.values.count { it.payload.roomId == roomId && it.payload.lamportClock == lamport }.toLong()
}

private class FakeCausalHoldRepository(
    private val messageRepo: FakeMessageRepository,
) : CausalHoldRepository {
    private val rows = mutableListOf<CausalHoldRow>()

    override suspend fun insert(gapId: Uuid, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Long) {
        rows.add(CausalHoldRow(gapId, missingPrevId, orphanedMessageId, detectedTimestamp))
    }

    override suspend fun findByMissingPrevId(missingPrevId: Uuid): List<CausalHoldRow> =
        rows.filter { it.missingPrevId == missingPrevId }

    override suspend fun findByRoom(roomId: RoomId): List<CausalHoldRow> =
        // Mirror the SQL JOIN: causal_hold belongs to the room of its orphaned message.
        rows.filter { row ->
            messageRepo.findById(row.orphanedMessageId)?.payload?.roomId == roomId
        }

    override suspend fun findAll(): List<CausalHoldRow> = rows.toList()

    override suspend fun deleteByMissingPrevId(missingPrevId: Uuid) {
        rows.removeAll { it.missingPrevId == missingPrevId }
    }

    override suspend fun deleteByOrphanedMessageId(orphanedMessageId: Uuid) {
        rows.removeAll { it.orphanedMessageId == orphanedMessageId }
    }
}

private class FakeIdentityResolver(
    private val localAccountId: AccountId,
    private val localDeviceId: PeerId = PeerId("local-device-id"),
) : IdentityResolver {
    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = error("not used")
    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord = error("not used")
    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = localDeviceId
    override suspend fun getLocalAccountId(): AccountId = localAccountId
    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord = error("not used")
    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint = error("not used")
    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used")
    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = error("not used")
    override suspend fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used")
    override suspend fun resolvePeerX3dhRemoteKeys(deviceId: PeerId, signedPreKeyId: String?) = error("not used")
    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord = error("not used")
    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord = error("not used")
}

private class RecordingRouter : Router {
    private val _incomingMessages = MutableSharedFlow<MessagePayload>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<MessagePayload> = _incomingMessages.asSharedFlow()

    override val typingIndicators: Flow<TypingIndicatorEvent> = MutableSharedFlow()

    val sentTargets = mutableListOf<AccountId>()

    override suspend fun start() {}
    override suspend fun stop() {}
    override fun isRunning(): Boolean = true

    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
    ): SendMessageResult {
        sentTargets.add(target)
        return SendMessageResult(
            status = SendMessageStatus.SUCCESS,
            peersTotal = 1,
            peersQueued = 1,
            failureKind = null,
        )
    }

    override suspend fun sendTypingIndicator(targets: Collection<AccountId>, roomId: RoomId, interval: Duration) = Unit

    suspend fun emitIncoming(payload: MessagePayload) {
        _incomingMessages.emit(payload)
    }
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