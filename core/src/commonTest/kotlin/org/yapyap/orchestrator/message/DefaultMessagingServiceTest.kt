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
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.DefaultDagEngine
import org.yapyap.orchestrator.dag.MessageDraft
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.pipeline.DefaultInboundMessagePipeline
import org.yapyap.orchestrator.runtime.message.DefaultMessagingService
import org.yapyap.orchestrator.runtime.message.IncomingMessageEvent
import org.yapyap.orchestrator.runtime.message.MessageDisplayItem
import org.yapyap.persistence.db.*
import org.yapyap.persistence.messaging.*
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.RoomEventPayload
import org.yapyap.routing.router.*
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
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
    private lateinit var clock: FakeClock
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
        clock = FakeClock(epochSeconds(1_000_000L))
        router = RecordingRouter()
        roomMembershipRepo = FakeRoomRepository(mutableMapOf(roomId to listOf(localAccount, remoteAccount)))
        dagEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            roomRepository = roomMembershipRepo,
            identityResolver = identityResolver,
            clock = clock,
            signatureProvider = FakeSignatureProvider(),
            cryptoProvider = DefaultCryptoProvider(),
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
    fun sendTextMessage_parkedFrontier_returnsHistoryIncomplete_andSendsNothing() =
        runTest(UnconfinedTestDispatcher()) {
            dagEngine.ingest(
                MessagePayload.Text(
                    messageId = Uuid.random(),
                    roomId = roomId,
                    senderAccountId = remoteAccount,
                    prevIds = listOf(Uuid.random()),
                    createdAt = clock.now(),
                    text = "waiting for prev",
                    authorDeviceId = PeerId("test-device"),
                    authorSignature = byteArrayOf(0x01, 0x02, 0x03),
                ),
            )
            val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
            val service = newService(this, pipeline)
            startStack(this, pipeline, service)

            val result = service.sendTextMessage(roomId, "hello from local")

            assertEquals(SendMessageStatus.FAILURE, result.status)
            assertEquals(SendFailureKind.HISTORY_INCOMPLETE, result.failureKind)
            assertEquals(0, result.peersTotal)
            assertEquals(0, result.peersQueued)
            assertTrue(router.sentTargets.isEmpty())
            assertEquals(1, messageRepo.byId.size)
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
        // Pre-seed two messages in the DAG (distinct timestamps for a deterministic order).
        dagEngine.append(roomId, MessageDraft.Text("msg-1"))
        clock.advanceBy(1L.seconds)
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

        // Structural genesis rule: only RoomCreated may have empty prevIds.
        // Seed a local base so the remote message has a resolvable parent.
        val base = dagEngine.append(roomId, MessageDraft.Text("base"))
        val remoteTimestamp = epochSeconds(1_000_500L)
        val incoming = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevIds = listOf(base.messageId),
            createdAt = remoteTimestamp,
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
            prevIds = listOf(prevUuid),
            createdAt = clock.now(),
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
        assertEquals(listOf(prevUuid), (items[1] as MessageDisplayItem.Gap).missingPrevIds)
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

        // Structural genesis rule: the missing parent itself needs a parent.
        val base = dagEngine.append(roomId, MessageDraft.Text("base"))
        // Orphan arrives.
        val orphan = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevIds = listOf(prevUuid),
            createdAt = epochSeconds(1_000_500L),
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
            prevIds = listOf(base.messageId),
            createdAt = epochSeconds(1_000_400L),
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
    fun incomingMessage_emitsSignal_onlyFromOthers() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val msg1Uuid = Uuid.random()
        val msg2Uuid = Uuid.random()

        val received = mutableListOf<IncomingMessageEvent>()
        val collectorJob = backgroundScope.launch { service.incomingMessageEvents.collect { received.add(it) } }
        advanceUntilIdle()

        // Structural genesis rule: only RoomCreated may have empty prevIds.
        val base = dagEngine.append(roomId, MessageDraft.Text("base"))
        // From remote → emits a content-free signal (no preview: the GUI re-pulls
        // roomPreview, so hidden messages never leak through notification).
        val remoteIncoming = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevIds = listOf(base.messageId),
            createdAt = clock.now(),
            text = "hi from remote",
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(remoteIncoming)
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(roomId, received[0].roomId)
        assertEquals(remoteAccount, received[0].senderAccountId)
        val item = received[0].item
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals("hi from remote", item.text)
        assertEquals(msg1Uuid, item.messageId)

        // From self → no event.
        received.clear()
        val selfIncoming = MessagePayload.Text(
            messageId = msg2Uuid,
            roomId = roomId,
            senderAccountId = localAccount,
            prevIds = listOf(msg1Uuid),
            createdAt = clock.now(),
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
    fun incomingMessage_hiddenAuthor_emitsNoEvent() = runTest(UnconfinedTestDispatcher()) {
        // Negative test (d3): a stranger-injection message is stored but the
        // author has no member row, so the emit-side policy filter must
        // suppress the notification — the event carries content, so the
        // filter (not contentlessness) is the enforcement.
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val received = mutableListOf<IncomingMessageEvent>()
        val collectorJob = backgroundScope.launch { service.incomingMessageEvents.collect { received.add(it) } }
        advanceUntilIdle()

        val base = dagEngine.append(roomId, MessageDraft.Text("base"))
        val stranger = AccountId("msg-stranger")
        router.emitIncoming(
            MessagePayload.Text(
                messageId = Uuid.random(),
                roomId = roomId,
                senderAccountId = stranger,
                prevIds = listOf(base.messageId),
                createdAt = clock.now(),
                text = "stranger smuggles",
                authorDeviceId = PeerId("test-device"),
                authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            )
        )
        advanceUntilIdle()

        // Stored (it went through the whole path) but never notified.
        assertTrue(messageRepo.byId.values.any { it.payload.senderAccountId == stranger })
        assertTrue(messageRepo.byId.values.none { it.verificationState == VerificationState.REJECTED })
        assertTrue(received.isEmpty())

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
            prevIds = emptyList(),
            createdAt = clock.now(),
            eventBytes = byteArrayOf(0x01),
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(globalEvent)
        advanceUntilIdle()

        // Window stays empty (GlobalEvent filtered out).
        assertEquals(0, window.displayItems.value.size)
        // No event: control-plane payloads have no display item, so the
        // collector's displayability gate stops them before the emit path.
        assertEquals(0, received.size)

        collectorJob.cancel()
    }

    @Test
    fun roomEvent_doesNotUpdateWindow_andDoesNotEmitEvent() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val window = service.openRoom(roomId, initialPageSize = 100)
        val received = mutableListOf<IncomingMessageEvent>()
        val collectorJob = backgroundScope.launch { service.incomingMessageEvents.collect { received.add(it) } }
        advanceUntilIdle()

        val base = dagEngine.append(roomId, MessageDraft.Text("base"))
        val roomEvent = MessagePayload.RoomEvent(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            prevIds = listOf(base.messageId),
            createdAt = clock.now(),
            eventBytes = RoomEventPayload.MemberAdd(remoteAccount).encode(),
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        router.emitIncoming(roomEvent)
        advanceUntilIdle()

        // Window stays empty (RoomEvent filtered out, never mapped to a display item).
        assertEquals(0, window.displayItems.value.size)
        assertEquals(0, received.size)

        collectorJob.cancel()
    }

    @Test
    fun loadOlder_paginatesBackward() = runTest(UnconfinedTestDispatcher()) {
        // Three messages: m1 (oldest), m2, m3 (newest).
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        clock.advanceBy(1L.seconds)
        val m2 = dagEngine.append(roomId, MessageDraft.Text("b"))
        clock.advanceBy(1L.seconds)
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

    // ------------------------------------------------------------------
    // roomPreview (docs/room events.md §3: latest *visible* message)
    // ------------------------------------------------------------------

    private suspend fun seedText(
        sender: AccountId,
        text: String,
        tick: Long,
        state: VerificationState = VerificationState.VERIFIED,
    ): MessagePayload.Text {
        val msg = MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = sender,
            prevIds = emptyList(),
            createdAt = epochSeconds(tick),
            text = text,
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        messageRepo.insert(
            payload = msg,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = state,
        )
        return msg
    }

    @Test
    fun roomPreview_returnsNewestVisibleMessage() = runTest(UnconfinedTestDispatcher()) {
        seedText(localAccount, "old", tick = 1L)
        seedText(remoteAccount, "new", tick = 2L)
        val service = newService(this)

        val preview = service.roomPreview(roomId)

        assertNotNull(preview)
        assertEquals(remoteAccount, preview.senderAccountId)
        val item = preview.item
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals("new", item.text)
        assertEquals(epochSeconds(2L), preview.timestamp)
    }

    @Test
    fun roomPreview_skipsNeverMemberMessages() = runTest(UnconfinedTestDispatcher()) {
        // Negative test (d3): the newest message is VERIFIED stranger-injection
        // content — no room_members row — and must never surface as the preview.
        val stranger = AccountId("msg-stranger")
        seedText(remoteAccount, "member says hi", tick = 1L)
        seedText(stranger, "stranger smuggles", tick = 2L)
        val service = newService(this)

        val preview = service.roomPreview(roomId)

        assertNotNull(preview)
        assertEquals(remoteAccount, preview.senderAccountId)
        val item = preview.item
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals("member says hi", item.text)
    }

    @Test
    fun roomPreview_returnsRemovedMemberMessage() = runTest(UnconfinedTestDispatcher()) {
        // Anti-trap: REMOVED is a badge, not a hide — the row exists, so the
        // message stays visible (the GUI badges it via the status read).
        roomMembershipRepo.statuses[roomId to remoteAccount] = RoomMemberStatus.REMOVED
        seedText(remoteAccount, "before i left", tick = 1L)
        val service = newService(this)

        val preview = service.roomPreview(roomId)

        assertNotNull(preview)
        val item = preview.item
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals("before i left", item.text)
    }

    @Test
    fun roomPreview_skipsRejected_returnsPending() = runTest(UnconfinedTestDispatcher()) {
        // The page read is verdict-filtered exactly as before (PENDING renders
        // per the sprint-2 orphan UX; REJECTED never does).
        seedText(remoteAccount, "pending identity", tick = 1L, state = VerificationState.PENDING)
        seedText(remoteAccount, "proven forgery", tick = 2L, state = VerificationState.REJECTED)
        val service = newService(this)

        val preview = service.roomPreview(roomId)

        assertNotNull(preview)
        val item = preview.item
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals("pending identity", item.text)
    }

    @Test
    fun roomPreview_allHidden_returnsNull() = runTest(UnconfinedTestDispatcher()) {
        seedText(AccountId("msg-stranger"), "nobody vouches", tick = 1L)
        val service = newService(this)

        assertNull(service.roomPreview(roomId))
    }

    @Test
    fun roomPreview_passesFullTextToGui() = runTest(UnconfinedTestDispatcher()) {
        // Truncation is a GUI concern: the service hands over the full text.
        val long = "x".repeat(120)
        seedText(remoteAccount, long, tick = 1L)
        val service = newService(this)

        val preview = service.roomPreview(roomId)

        assertNotNull(preview)
        val item = preview.item
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals(long, item.text)
    }

    @Test
    fun roomPreview_deferredMember_convergesWhenRowLands() = runTest(UnconfinedTestDispatcher()) {
        // The author's account row has not landed yet: no room_members row, so
        // the (genuine, VERIFIED) message hides exactly like a never-member's.
        val latecomer = AccountId("msg-latecomer")
        seedText(latecomer, "i was here all along", tick = 1L)
        val service = newService(this)
        assertNull(service.roomPreview(roomId))

        // GLOBAL commit lands the identity; the re-fold commits the member row.
        roomMembershipRepo.members[roomId] = listOf(localAccount, remoteAccount, latecomer)

        val preview = service.roomPreview(roomId)
        assertNotNull(preview)
        val item = preview.item
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals("i was here all along", item.text)
    }

    @Test
    fun sendTextMessage_fanOut_skipsRemovedMembers() = runTest(UnconfinedTestDispatcher()) {
        roomMembershipRepo.statuses[roomId to remoteAccount] = RoomMemberStatus.REMOVED
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val result = service.sendTextMessage(roomId, "hello")

        assertEquals(SendMessageStatus.SUCCESS, result.status)
        // REMOVED rows never receive fan-out: only the local account is targeted.
        assertEquals(listOf(localAccount), router.sentTargets)
    }
}

/* ---------- fakes (pure Kotlin, commonTest-safe) ---------- */

private class FakeRoomRepository(
    val members: MutableMap<RoomId, List<AccountId>>,
    /** Per-(room, account) status overrides; absent entries read as ACTIVE. */
    val statuses: MutableMap<Pair<RoomId, AccountId>, RoomMemberStatus> = mutableMapOf(),
) : RoomRepository {
    override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> =
        // Mirror the ACTIVE-only access read.
        members[roomId].orEmpty().filter { statuses[roomId to it] != RoomMemberStatus.REMOVED }

    override suspend fun memberStatusesOfRoom(roomId: RoomId): List<RoomMemberRecord> {
        val accounts = (members[roomId].orEmpty() + statuses.keys.filter { it.first == roomId }.map { it.second })
            .toSet()
        return accounts.map { account ->
            RoomMemberRecord(account, RoomMemberRole.MEMBER, statuses[roomId to account] ?: RoomMemberStatus.ACTIVE)
        }
    }

    override suspend fun memberRowOf(roomId: RoomId, accountId: AccountId): RoomMemberRecord? {
        // Mirror production: a committed member row exists for listed members
        // (absent status overrides read as ACTIVE); strangers have no row.
        if (accountId !in members[roomId].orEmpty() && (roomId to accountId) !in statuses) return null
        return RoomMemberRecord(
            accountId,
            RoomMemberRole.MEMBER,
            statuses[roomId to accountId] ?: RoomMemberStatus.ACTIVE
        )
    }

    override suspend fun roomsOfPeer(peerId: PeerId): List<RoomId> = members.keys.toList()

    override suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String) = Unit

    override suspend fun upsertMember(
        roomId: RoomId,
        accountId: AccountId,
        role: RoomMemberRole,
        status: RoomMemberStatus,
        removalNodeId: Uuid?,
    ) = Unit

    override suspend fun removeMember(roomId: RoomId, accountId: AccountId) = Unit

    override suspend fun allChatRoomIds(): List<RoomId> = members.keys.toList()

    override suspend fun roomOf(roomId: RoomId): RoomRecord? = null

    override suspend fun mergeRoomFromGenesis(roomId: RoomId, name: String, type: RoomType, spaceId: String?) = Unit

    override suspend fun removeRoomMembersNotIn(roomId: RoomId, keep: Collection<AccountId>) = Unit
}

private class FakeMessageRepository : MessageRepository {
    val byId = mutableMapOf<Uuid, MessageRow>()

    private val parentIds = mutableMapOf<Uuid, MutableList<Uuid>>()

    override suspend fun insert(
        payload: MessagePayload,
        isOrphaned: Boolean,
        ancestryComplete: Boolean,
        verificationState: VerificationState,
    ): Boolean {
        if (byId.containsKey(payload.messageId)) {
            // INSERT OR IGNORE semantics.
            return true
        }
        byId[payload.messageId] = MessageRow(payload, isOrphaned, verificationState, ancestryComplete)
        return true
    }

    override suspend fun findById(messageId: Uuid): MessageRow? = byId[messageId]

    override suspend fun findRoomFrontier(roomId: RoomId): List<MessageRow> {
        // Mirror selectRoomFrontier (VERIFIED-only chainability).
        val chainable = byId.values.filter {
            it.payload.roomId == roomId && it.ancestryComplete && it.verificationState == VerificationState.VERIFIED
        }
        val referenced = chainable
            .flatMap { child -> parentIds[child.payload.messageId].orEmpty() }
            .toSet()
        return chainable.filter { it.payload.messageId !in referenced }
    }

    override suspend fun findParents(messageId: Uuid): List<Uuid> =
        parentIds[messageId].orEmpty().toList()

    override suspend fun insertParent(messageId: Uuid, parentId: Uuid) {
        parentIds.getOrPut(messageId) { mutableListOf() }.add(parentId)
    }

    override suspend fun findChildrenInRoom(parentId: Uuid, roomId: RoomId): List<MessageRow> =
        byId.values.filter { row ->
            row.payload.roomId == roomId && parentId in parentIds[row.payload.messageId].orEmpty()
        }

    override suspend fun findMessagesInRoomPageDesc(
        roomId: RoomId,
        limit: Int,
        cursor: MessageCursor?,
    ): List<MessageRow> {
        val all = byId.values
            .filter { it.payload.roomId == roomId && it.verificationState != VerificationState.REJECTED }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAt }
                    .thenByDescending { it.payload.messageId }
            )
        val filtered = if (cursor == null) {
            all
        } else {
            // Strictly older than the cursor row (both key sub-comparisons).
            all.filter { row ->
                val rowCreated = row.payload.createdAt
                val rowId = row.payload.messageId
                rowCreated < cursor.createdAt ||
                        (rowCreated == cursor.createdAt && rowId < cursor.messageId)
            }
        }
        return filtered.take(limit)
    }

    override suspend fun findAllInRoom(roomId: RoomId): List<MessageRow> =
        byId.values
            .filter { it.payload.roomId == roomId }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAt }
                    .thenByDescending { it.payload.messageId }
            )

    override suspend fun hasMessages(roomId: RoomId): Boolean =
        byId.values.any { it.payload.roomId == roomId }

    override suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(isOrphaned = isOrphaned)
    }

    override suspend fun updateAncestryComplete(messageId: Uuid, complete: Boolean) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(ancestryComplete = complete)
    }

    override suspend fun updateVerificationState(messageId: Uuid, state: VerificationState) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(verificationState = state)
    }

    override suspend fun findPendingByAuthor(deviceId: PeerId): List<MessageRow> =
        byId.values
            .filter { it.payload.authorDeviceId == deviceId && it.verificationState == VerificationState.PENDING }
            .toList()

    override suspend fun findAllPending(): List<MessageRow> =
        byId.values.filter { it.verificationState == VerificationState.PENDING }.toList()

    override suspend fun findFoldableInRoom(roomId: RoomId): List<MessageRow> =
        byId.values.filter {
            it.payload.roomId == roomId &&
                    it.ancestryComplete &&
                    it.verificationState == VerificationState.VERIFIED
        }
}

private class FakeCausalHoldRepository(
    private val messageRepo: FakeMessageRepository,
) : CausalHoldRepository {
    private val rows = mutableListOf<CausalHoldRow>()

    override suspend fun insert(gapId: Uuid, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Instant) {
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

    override suspend fun countByOrphan(orphanedMessageId: Uuid): Long =
        rows.count { it.orphanedMessageId == orphanedMessageId }.toLong()

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
    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus = IdentityStatus.ACTIVE
    override suspend fun isLocalAccountAdmin(): Boolean = error("not used")
    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = localDeviceId
    override suspend fun getLocalAccountId(): AccountId = localAccountId
    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord = error("not used")
    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint = error("not used")
    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used")
    override suspend fun getAllPeers(): List<PeerId> = error("not used")
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

    override val bootstrapPackets: Flow<BootstrapPacketEvent> = MutableSharedFlow()

    override val pingPayloads: Flow<PingFrontiers> = MutableSharedFlow()

    val sentTargets = mutableListOf<AccountId>()

    override suspend fun start() {}
    override suspend fun stop() {}
    override fun isRunning(): Boolean = true
    override suspend fun announceOnline() = Unit

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

    override suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint?,
        sharedSecret: ByteArray?,
    ) = Unit

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