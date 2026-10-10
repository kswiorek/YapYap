package org.yapyap.orchestrator.message

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
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
import org.yapyap.orchestrator.pipeline.DefaultInboundMessagePipeline
import org.yapyap.orchestrator.runtime.message.*
import org.yapyap.persistence.db.VerificationState
import org.yapyap.persistence.messaging.*
import org.yapyap.protocol.*
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
        messageRepo.roomRepository = roomMembershipRepo
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
        messageRepository = messageRepo,
        causalHoldRepository = causalHoldRepo,
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

        val sent = assertIs<SendTextResult.Sent>(result)
        // Report counts other members only — the self fan-out (multi-device push) is excluded.
        assertEquals(FanoutReport(membersTotal = 1, membersQueued = 1, membersPullOnly = 0), sent.fanout)
        // Fan-out still reaches all room members — no account-level self-filter (router handles device skip).
        assertEquals(2, router.sentTargets.size)
        assertTrue(router.sentTargets.contains(remoteAccount))
        assertTrue(router.sentTargets.contains(localAccount))

        // DAG contains the message.
        val messages = messageRepo.findAllInRoom(roomId).map { it.payload }
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

            val refused = assertIs<SendTextResult.Refused>(result)
            assertEquals(SendRefusal.HistoryIncomplete, refused.reason)
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

        val sent = assertIs<SendTextResult.Sent>(result)
        assertEquals(FanoutReport(membersTotal = 0, membersQueued = 0, membersPullOnly = 0), sent.fanout)
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

        val refused = assertIs<SendTextResult.Refused>(result)
        assertEquals(SendRefusal.TooLarge, refused.reason)
        assertEquals(0, router.sentTargets.size)
        assertEquals(0, messageRepo.findAllInRoom(roomId).size)
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
    fun roomPreview_removedMemberInClosureMessage_renders() = runTest(UnconfinedTestDispatcher()) {
        // Member-era history renders: the message is an ancestor of the removal
        // node, so it is inside the removal boundary (no badge anymore — the
        // boundary is the whole rule).
        val era = seedLinkedText(remoteAccount, "before i left", tick = 1L, parents = emptyList())
        seedRemoval(remoteAccount, parents = listOf(era.messageId), tick = 2L)
        val service = newService(this)

        val preview = service.roomPreview(roomId)

        assertNotNull(preview)
        val item = preview.item
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals("before i left", item.text)
    }

    @Test
    fun roomPreview_removedMemberPostRemovalOnly_returnsNull() = runTest(UnconfinedTestDispatcher()) {
        // Negative test (d3): the only text from the removed author provably
        // postdates the removal (descends from the removal node) — hidden, and
        // the removal event itself has no display item, so nothing renders.
        val removal = seedRemoval(remoteAccount, parents = emptyList(), tick = 2L)
        seedLinkedText(remoteAccount, "post removal spam", tick = 3L, parents = listOf(removal.messageId))
        val service = newService(this)

        assertNull(service.roomPreview(roomId))
    }

    @Test
    fun roomPreview_removedMemberBackdatedMessage_hidden() = runTest(UnconfinedTestDispatcher()) {
        // Negative test (d3): a backdated forgery chains onto pre-removal tips but
        // is concurrent with the removal — not its ancestor — so it hides and the
        // preview falls back to the member-era message.
        val era = seedLinkedText(remoteAccount, "era", tick = 1L, parents = emptyList())
        seedRemoval(remoteAccount, parents = listOf(era.messageId), tick = 2L)
        seedLinkedText(remoteAccount, "backdated forgery", tick = 3L, parents = listOf(era.messageId))
        val service = newService(this)

        val preview = service.roomPreview(roomId)

        assertNotNull(preview)
        val item = preview.item
        assertTrue(item is MessageDisplayItem.Text)
        assertEquals("era", item.text)
    }

    @Test
    fun removedMemberOrphan_hiddenInWindow_stillHiddenAfterChaining_visibleAfterReAdd() =
        runTest(UnconfinedTestDispatcher()) {
            val era = seedLinkedText(remoteAccount, "era", tick = 1L, parents = emptyList())
            seedRemoval(remoteAccount, parents = listOf(era.messageId), tick = 2L)
            val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
            val service = newService(this, pipeline)
            startStack(this, pipeline, service)

            val window = service.openRoom(roomId, initialPageSize = 100)
            advanceUntilIdle()
            assertEquals(1, window.displayItems.value.size)

            // Orphan from the removed author with a fabricated missing parent:
            // hidden from the live window (no text, no gap indicator).
            val fabricated = Uuid.random()
            val orphan = MessagePayload.Text(
                messageId = Uuid.random(),
                roomId = roomId,
                senderAccountId = remoteAccount,
                prevIds = listOf(fabricated),
                createdAt = epochSeconds(3L),
                text = "orphaned spam",
                authorDeviceId = PeerId("test-device"),
                authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            )
            router.emitIncoming(orphan)
            advanceUntilIdle()

            assertEquals(1, window.displayItems.value.size)
            assertEquals("era", (window.displayItems.value[0] as MessageDisplayItem.Text).text)
            assertFalse(messageRepo.isRenderable(roomId, orphan.messageId))

            // The missing parent arrives and the orphan chains — still outside the
            // removal closure (the closure is frozen at the removal node), so still
            // hidden.
            val lateParent = MessagePayload.Text(
                messageId = fabricated,
                roomId = roomId,
                senderAccountId = remoteAccount,
                prevIds = listOf(era.messageId),
                createdAt = epochSeconds(4L),
                text = "late parent",
                authorDeviceId = PeerId("test-device"),
                authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            )
            messageRepo.insert(lateParent, isOrphaned = false, ancestryComplete = true, verificationState = VerificationState.VERIFIED)
            messageRepo.insertParent(lateParent.messageId, era.messageId)
            assertFalse(messageRepo.isRenderable(roomId, orphan.messageId))
            assertFalse(messageRepo.isRenderable(roomId, lateParent.messageId))

            // Re-add reveals everything again — nothing was lost.
            roomMembershipRepo.statuses[roomId to remoteAccount] = RoomMemberStatus.ACTIVE
            assertTrue(messageRepo.isRenderable(roomId, orphan.messageId))
            window.close()
        }

    @Test
    fun removedMemberReAdded_messagesVisibleAgain() = runTest(UnconfinedTestDispatcher()) {
        val era = seedLinkedText(remoteAccount, "era", tick = 1L, parents = emptyList())
        val removal = seedRemoval(remoteAccount, parents = listOf(era.messageId), tick = 2L)
        seedLinkedText(remoteAccount, "post removal", tick = 3L, parents = listOf(removal.messageId))
        val service = newService(this)

        // While removed, the newest visible message is the member-era one.
        assertEquals("era", ((service.roomPreview(roomId)!!.item) as MessageDisplayItem.Text).text)

        roomMembershipRepo.statuses[roomId to remoteAccount] = RoomMemberStatus.ACTIVE

        assertEquals("post removal", ((service.roomPreview(roomId)!!.item) as MessageDisplayItem.Text).text)
    }

    /**
     * Seeds a text message with explicit parent edges (the engine records edges
     * separately from the row; direct repo inserts must do the same).
     */
    private suspend fun seedLinkedText(
        sender: AccountId,
        text: String,
        tick: Long,
        parents: List<Uuid>,
    ): MessagePayload.Text {
        val msg = seedText(sender, text, tick)
        for (parent in parents) messageRepo.insertParent(msg.messageId, parent)
        return msg
    }

    /**
     * Stores a removal node (a RoomEvent has no display item, like production)
     * and flips the target's row to REMOVED with the node as the boundary.
     */
    private suspend fun seedRemoval(
        target: AccountId,
        parents: List<Uuid>,
        tick: Long,
    ): MessagePayload.RoomEvent {
        val removal = MessagePayload.RoomEvent(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = localAccount,
            prevIds = parents,
            createdAt = epochSeconds(tick),
            eventBytes = RoomEventPayload.MemberRemove(target, null).encode(),
            authorDeviceId = PeerId("test-device"),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        messageRepo.insert(
            removal,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.VERIFIED,
        )
        for (parent in parents) messageRepo.insertParent(removal.messageId, parent)
        roomMembershipRepo.statuses[roomId to target] = RoomMemberStatus.REMOVED
        roomMembershipRepo.removalNodes[roomId to target] = removal.messageId
        return removal
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

        val sent = assertIs<SendTextResult.Sent>(result)
        // The self fan-out (multi-device push) is excluded from the report.
        assertEquals(FanoutReport(membersTotal = 0, membersQueued = 0, membersPullOnly = 0), sent.fanout)
        // REMOVED rows never receive fan-out: only the local account is targeted.
        assertEquals(listOf(localAccount), router.sentTargets)
    }

    @Test
    fun sendTextMessage_removedLocalAccount_refused() = runTest(UnconfinedTestDispatcher()) {
        // A removed local account cannot append to the room — refused before any
        // write, so the sender's own messages cannot silently vanish either.
        roomMembershipRepo.statuses[roomId to localAccount] = RoomMemberStatus.REMOVED
        roomMembershipRepo.removalNodes[roomId to localAccount] = Uuid.random()
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        val result = service.sendTextMessage(roomId, "should not send")

        val refused = assertIs<SendTextResult.Refused>(result)
        assertEquals(SendRefusal.NotMember, refused.reason)
        assertTrue(router.sentTargets.isEmpty())
        assertTrue(messageRepo.byId.isEmpty())
    }

    @Test
    fun typingIndicator_fromRemovedOrStranger_ignored() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        val service = newService(this, pipeline)
        startStack(this, pipeline, service)

        // Sanity: an ACTIVE member's typing registers (then expires via idle-timeout).
        router.emitTyping(TypingIndicatorEvent(remoteAccount, roomId, 3600.seconds, clock.now()))
        advanceTimeBy(7201.seconds)
        assertTrue(service.typingState.value[roomId].isNullOrEmpty())

        // A removed member's typing is ignored — never registers (its idle-timeout
        // would only fire 2h after receipt, so absence here means ignored, not expired).
        roomMembershipRepo.statuses[roomId to remoteAccount] = RoomMemberStatus.REMOVED
        roomMembershipRepo.removalNodes[roomId to remoteAccount] = Uuid.random()
        router.emitTyping(TypingIndicatorEvent(remoteAccount, roomId, 3600.seconds, clock.now()))
        advanceTimeBy(1.seconds)
        assertTrue(service.typingState.value[roomId].isNullOrEmpty())

        // A never-member's typing is ignored too.
        router.emitTyping(TypingIndicatorEvent(AccountId("msg-stranger"), roomId, 3600.seconds, clock.now()))
        advanceTimeBy(1.seconds)
        assertTrue(service.typingState.value[roomId].isNullOrEmpty())
    }
}

/* ---------- fakes (pure Kotlin, commonTest-safe) ---------- */

private class FakeRoomRepository(
    val members: MutableMap<RoomId, List<AccountId>>,
    /** Per-(room, account) status overrides; absent entries read as ACTIVE. */
    val statuses: MutableMap<Pair<RoomId, AccountId>, RoomMemberStatus> = mutableMapOf(),
    /** Defining removal node per (room, account); read on REMOVED rows. */
    val removalNodes: MutableMap<Pair<RoomId, AccountId>, Uuid> = mutableMapOf(),
) : RoomRepository {
    override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> =
        // Mirror the ACTIVE-only access read.
        members[roomId].orEmpty().filter { statuses[roomId to it] != RoomMemberStatus.REMOVED }

    override suspend fun memberStatusesOfRoom(roomId: RoomId): List<RoomMemberRecord> {
        val accounts = (members[roomId].orEmpty() + statuses.keys.filter { it.first == roomId }.map { it.second })
            .toSet()
        return accounts.map { account ->
            RoomMemberRecord(
                account,
                RoomMemberRole.MEMBER,
                statuses[roomId to account] ?: RoomMemberStatus.ACTIVE,
                removalNodes[roomId to account],
            )
        }
    }

    override suspend fun memberRowOf(roomId: RoomId, accountId: AccountId): RoomMemberRecord? {
        // Mirror production: a committed member row exists for listed members
        // (absent status overrides read as ACTIVE); strangers have no row.
        if (accountId !in members[roomId].orEmpty() && (roomId to accountId) !in statuses) return null
        return RoomMemberRecord(
            accountId,
            RoomMemberRole.MEMBER,
            statuses[roomId to accountId] ?: RoomMemberStatus.ACTIVE,
            removalNodes[roomId to accountId],
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

    /** Wired in setup to the room membership fake (the renderable queries join membership). */
    var roomRepository: FakeRoomRepository? = null

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

    override suspend fun renderableMessagesInRoom(
        roomId: RoomId,
        limit: Int,
        cursor: MessageCursor?,
    ): List<MessageRow> {
        val all = byId.values
            .filter { row ->
                row.payload.roomId == roomId &&
                        row.verificationState != VerificationState.REJECTED &&
                        isVisible(roomId, row.payload.senderAccountId, row.payload.messageId)
            }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAt }
                    .thenByDescending { it.payload.messageId }
            )
        val filtered = if (cursor == null) {
            all
        } else {
            all.filter { row ->
                val rowCreated = row.payload.createdAt
                val rowId = row.payload.messageId
                rowCreated < cursor.createdAt ||
                        (rowCreated == cursor.createdAt && rowId < cursor.messageId)
            }
        }
        return filtered.take(limit)
    }

    override suspend fun isRenderable(roomId: RoomId, messageId: Uuid): Boolean {
        val row = byId[messageId] ?: return false
        if (row.payload.roomId != roomId) return false
        if (row.verificationState == VerificationState.REJECTED) return false
        return isVisible(roomId, row.payload.senderAccountId, messageId)
    }

    private suspend fun isVisible(roomId: RoomId, sender: AccountId, messageId: Uuid): Boolean {
        // Mirror the SQL visibility predicate: an ACTIVE row renders; a REMOVED
        // row renders only inside its removal node's ancestor closure (the member
        // era); no row never renders. The closure walks stored parent edges from
        // the removal node — orphans from removed authors are unreachable from a
        // chainable root, so no explicit orphan check is needed.
        val rooms = roomRepository ?: error("roomRepository must be wired for renderable queries")
        val member = rooms.memberRowOf(roomId, sender) ?: return false
        if (member.status == RoomMemberStatus.ACTIVE) return true
        val removalNode = member.removalNodeId ?: return false
        val seen = HashSet<Uuid>()
        val queue = ArrayDeque<Uuid>()
        seen.add(removalNode)
        queue.add(removalNode)
        while (queue.isNotEmpty()) {
            for (parent in parentIds[queue.removeFirst()].orEmpty()) {
                if (seen.add(parent)) queue.add(parent)
            }
        }
        return messageId in seen
    }

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
    private val rows = mutableListOf<Gap>()

    override suspend fun insert(gapId: Uuid, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Instant) {
        rows.add(Gap(gapId, missingPrevId, orphanedMessageId, detectedTimestamp))
    }

    override suspend fun findByMissingPrevId(missingPrevId: Uuid): List<Gap> =
        rows.filter { it.missingPrevId == missingPrevId }

    override suspend fun findByRoom(roomId: RoomId): List<Gap> =
        // Mirror the SQL JOIN: causal_hold belongs to the room of its orphaned message.
        rows.filter { row ->
            messageRepo.findById(row.orphanedMessageId)?.payload?.roomId == roomId
        }

    override suspend fun findAll(): List<Gap> = rows.toList()

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
    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? = error("not used")
    override suspend fun isLocalAccountAdmin(): Boolean = error("not used")
    override suspend fun isLocalAccountOwner(): Boolean = error("not used")
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

    private val _typingIndicators = MutableSharedFlow<TypingIndicatorEvent>(extraBufferCapacity = 64)
    override val typingIndicators: Flow<TypingIndicatorEvent> = _typingIndicators.asSharedFlow()

    override val bootstrapPackets: Flow<BootstrapPacketEvent> = MutableSharedFlow()

    override val pingPayloads: Flow<PingFrontiers> = MutableSharedFlow()

    override val onlineAccounts: Flow<Set<AccountId>> = emptyFlow()

    val sentTargets = mutableListOf<AccountId>()
    override suspend fun start() {}
    override suspend fun stop() {}
    override fun isRunning(): Boolean = true
    override suspend fun announceOnline() = Unit

    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
    ): AccountPushReport {
        sentTargets.add(target)
        return AccountPushReport(
            devicesTotal = 1,
            devicesQueued = 1,
            devicesDeferred = 0,
            devicesFailed = 0,
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

    suspend fun emitTyping(event: TypingIndicatorEvent) {
        _typingIndicators.emit(event)
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