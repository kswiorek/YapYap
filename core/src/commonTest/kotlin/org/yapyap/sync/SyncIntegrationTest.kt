package org.yapyap.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.DefaultDagEngine
import org.yapyap.orchestrator.pipeline.DefaultInboundMessagePipeline
import org.yapyap.orchestrator.sync.DefaultSyncCoordinator
import org.yapyap.persistence.db.VerificationState
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.*
import org.yapyap.routing.router.*
import org.yapyap.routing.sync.DefaultSyncPayloadProvider
import org.yapyap.routing.sync.SyncHandler
import org.yapyap.routing.sync.SyncRetryProcessor
import org.yapyap.testfixtures.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * End-to-end sync integration test.
 *
 * Wires the real orchestration + routing sync stack together (DagEngine, InboundMessagePipeline,
 * SyncCoordinator, SyncRetryProcessor, SyncHandler, DefaultSyncPayloadProvider) across two logical
 * nodes (local requester + remote responder) whose recording Tor transports are relayed by hand.
 * Verifies that a missing message is actually requested over the wire and, when the response is
 * ingested back, the gap is closed and the pending sync is deleted.
 */
class SyncIntegrationTest {

    private val now = 10_000L

    private val localAccount = AccountId("it-local-account")
    private val remoteAccount = AccountId("it-remote-account")
    private val localDevice = PeerId("it-local-device")
    private val remoteDevice = PeerId("it-remote-device")
    private val roomId = RoomId(Uuid.random())

    private val localTime = FakeClock(epochSeconds(now))

    // ------------------------------------------------------------------
    // Local node
    // ------------------------------------------------------------------

    private val localMessageRepo = FakeMessageRepository()
    private val localCausalHold = FakeCausalHoldRepository(localMessageRepo)
    private val localRoomRepo = FakeRoomRepository(mapOf(roomId to listOf(localAccount, remoteAccount)))
    private val localIdentity = FakeIdentityResolver(localAccount, localDevice)
    private val dagEngine = DefaultDagEngine(
        messageRepository = localMessageRepo,
        causalHoldRepository = localCausalHold,
        roomRepository = localRoomRepo,
        identityResolver = localIdentity,
        signatureProvider = FakeSignatureProvider(),
        cryptoProvider = DefaultCryptoProvider(),
        clock = localTime,
    )
    private val router = RecordingRouter()
    private val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
    private val pendingRepo = FakePendingSyncRepository(
        frontierOf = { room -> localMessageRepo.findRoomFrontier(room).map { it.payload.messageId } },
    )
    private val coordinator = DefaultSyncCoordinator(
        pipeline = pipeline,
        roomRepository = localRoomRepo,
        messageRepository = localMessageRepo,
        pendingSyncRepository = pendingRepo,
        clock = localTime,
        orchestratorConfig = MutableStateFlow(OrchestratorConfig(syncGracePeriod = Duration.ZERO)),
    )

    private fun textMsg(
        messageId: Uuid = Uuid.random(),
        prevIds: List<Uuid>,
    ): MessagePayload.Text =
        MessagePayload.Text(
            messageId = messageId,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDevice,
            authorSignature = byteArrayOf(1),
            prevIds = prevIds,
            createdAt = epochSeconds(0L),
            text = "m",
        )

    /**
     * Seeds [repo] the way the DagEngine would: row insert plus one parent edge per
     * prevId (the frontier query reads edges, not payload fields).
     */
    private suspend fun seed(
        repo: FakeMessageRepository,
        msg: MessagePayload.Text,
        isOrphaned: Boolean,
        ancestryComplete: Boolean,
    ) {
        repo.insert(
            msg,
            isOrphaned = isOrphaned,
            ancestryComplete = ancestryComplete,
            verificationState = VerificationState.VERIFIED,
        )
        for (parentId in msg.prevIds) {
            repo.insertParent(msg.messageId, parentId)
        }
    }

    private suspend fun awaitPendingSyncCount(count: Int) {
        while (pendingRepo.all().size < count) {
            yield()
        }
    }

    private suspend fun awaitPendingSyncEmpty() {
        while (pendingRepo.all().isNotEmpty()) {
            yield()
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * Gap sync: local ingests an orphan (prev missing) -> a pending sync is created -> the retry
     * processor sends a SyncRequest -> the remote payload provider returns the missing message ->
     * it is ingested back -> gap closes and the sync is deleted.
     */
    @Test
    fun gapSync_missingMessageRequestedAndReceived_gapClosesAndSyncDeleted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Local already has the anchor message.
            val anchor = textMsg(prevIds = emptyList())
            seed(localMessageRepo, anchor, isOrphaned = false, ancestryComplete = true)

            // Remote has the full chain; local only has the anchor. msg2 is the orphan on local.
            val remoteMessageRepo = FakeMessageRepository()
            val m1 = textMsg(prevIds = listOf(anchor.messageId))
            val m2 = textMsg(prevIds = listOf(m1.messageId))
            seed(remoteMessageRepo, anchor, isOrphaned = false, ancestryComplete = true)
            seed(remoteMessageRepo, m1, isOrphaned = false, ancestryComplete = true)
            seed(remoteMessageRepo, m2, isOrphaned = false, ancestryComplete = true)

            val localStack = buildSyncRoutingStack(
                localDevice = testDeviceIdentity(localDevice),
                peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
                torByPeer = mutableMapOf(remoteDevice to TorEndpoint("it-remote.onion", 80)),
                clock = FakeClock(epochSeconds(now)),
            )
            val remoteStack = buildSyncRoutingStack(
                localDevice = testDeviceIdentity(remoteDevice),
                peersByAccount = mapOf(localAccount to listOf(localDevice)),
                torByPeer = mutableMapOf(localDevice to TorEndpoint("it-local.onion", 80)),
                clock = FakeClock(epochSeconds(now)),
            )
            val retryProcessor = SyncRetryProcessor(
                ctx = localStack.ctx,
                pendingSyncs = pendingRepo,
                systemSender = localStack.systemSender,
                peerPolicy = FixedSyncPeerPolicy(nextDevice = remoteDevice),
                peerAvailabilityRegistry = PeerAvailabilityRegistry(
                    localStack.ctx.clock,
                    MutableStateFlow(RouterConfig()),
                    FakePeerAvailabilityStore()
                ),
                maxIdlePoll = MutableStateFlow(1.seconds),
            )
            val remoteHandler = SyncHandler(
                outboundMessenger = remoteStack.outboundMessenger,
                syncPayloadProvider = DefaultSyncPayloadProvider(
                    remoteMessageRepo,
                    MutableStateFlow(RouterConfig()),
                    FakeRoomRepository(
                        mapOf(roomId to listOf(localAccount, remoteAccount)),
                        mapOf(
                            localAccount to listOf(localDevice),
                            remoteAccount to listOf(remoteDevice),
                        ),
                    ),
                    remoteStack.identity,
                ),
                pendingSyncRepository = FakePendingSyncRepository(),
                systemSender = remoteStack.systemSender,
            )

            pipeline.start(scope)
            coordinator.start(scope)
            retryProcessor.runIn(scope)
            // The remote responder routes through the outbox; its dispatch loop must run
            // (production always runs it via router.start()).
            remoteStack.outboxProcessor.runIn(scope)

            // Ingest the orphan: pipeline emits BecameOrphan -> coordinator creates a pending sync.
            router.emitIncoming(m2)
            withTimeout(10.seconds) { awaitPendingSyncCount(1) }
            assertEquals(1, pendingRepo.all().size)
            val sync = pendingRepo.all().single()
            assertEquals(m1.messageId, sync.targetMessageId)

            // The retry processor sends a SyncRequest to the remote device.
            localStack.tor.awaitSendCount(1)
            val sent = localStack.tor.sends.single().second
            assertEquals(org.yapyap.protocol.PacketType.SYSTEM, sent.packetType)
            val syncRequest =
                SystemEnvelope.decode(sent.payload).decodePayload() as SystemPayload.SyncRequest
            assertEquals(roomId, syncRequest.roomId)
            assertEquals(sync.syncId, syncRequest.syncId)
            assertEquals(listOf(m1.messageId), syncRequest.missingIds)
            assertEquals(listOf(anchor.messageId), syncRequest.knownIds)

            // Remote handles the request and sends the missing message back.
            // Only m1 is returned: the responder serves the target plus its ancestry,
            // and the orphan m2 is already present locally (it triggered the sync).
            remoteHandler.onSyncRequested(syncRequest, sourceDevice = localDevice)
            withTimeout(10.seconds) { remoteStack.tor.awaitSendCount(1) }

            val returned = remoteStack.tor.sends.map { (_, bin) ->
                MessageEnvelope.decode(bin.payload).decodePayload()
            }
            assertTrue(returned.any { it.messageId == m1.messageId }, "missing message m1 not returned")

            // Relay each returned message back into the local node.
            returned.forEach { router.emitIncoming(it) }
            withTimeout(10.seconds) { awaitPendingSyncEmpty() }

            // Gap closed, sync deleted.
            assertTrue(pendingRepo.all().isEmpty(), "pending sync should be deleted after gap closure")
            assertTrue(localCausalHold.findByRoom(roomId).isEmpty(), "no open gaps should remain")
            assertEquals(3, localMessageRepo.byId.size)
        } finally {
            coordinator.stop()
            scope.cancel()
        }
    }

    /**
     * Frontier sync: a ping advertises a tip unknown locally -> one pending sync per tip ->
     * the retry processor sends a SyncRequest -> the remote returns the tip plus its ancestry
     * down to our known frontier -> they are ingested back and the sync is deleted.
     */
    @Test
    fun rangeSync_missingMessagesRequestedAndReceived_syncDeleted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Local has messages 0 and 1; remote has 0..4. Ping advertises the tip m4.
            val m0 = textMsg(prevIds = emptyList())
            val m1 = textMsg(prevIds = listOf(m0.messageId))
            seed(localMessageRepo, m0, isOrphaned = false, ancestryComplete = true)
            seed(localMessageRepo, m1, isOrphaned = false, ancestryComplete = true)

            val remoteMessageRepo = FakeMessageRepository()
            val m2 = textMsg(prevIds = listOf(m1.messageId))
            val m3 = textMsg(prevIds = listOf(m2.messageId))
            val m4 = textMsg(prevIds = listOf(m3.messageId))
            seed(remoteMessageRepo, m0, isOrphaned = false, ancestryComplete = true)
            seed(remoteMessageRepo, m1, isOrphaned = false, ancestryComplete = true)
            seed(remoteMessageRepo, m2, isOrphaned = false, ancestryComplete = true)
            seed(remoteMessageRepo, m3, isOrphaned = false, ancestryComplete = true)
            seed(remoteMessageRepo, m4, isOrphaned = false, ancestryComplete = true)

            val localStack = buildSyncRoutingStack(
                localDevice = testDeviceIdentity(localDevice),
                peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
                torByPeer = mutableMapOf(remoteDevice to TorEndpoint("it-remote.onion", 80)),
                clock = FakeClock(epochSeconds(now)),
            )
            val remoteStack = buildSyncRoutingStack(
                localDevice = testDeviceIdentity(remoteDevice),
                peersByAccount = mapOf(localAccount to listOf(localDevice)),
                torByPeer = mutableMapOf(localDevice to TorEndpoint("it-local.onion", 80)),
                clock = FakeClock(epochSeconds(now)),
            )
            val retryProcessor = SyncRetryProcessor(
                ctx = localStack.ctx,
                pendingSyncs = pendingRepo,
                systemSender = localStack.systemSender,
                peerPolicy = FixedSyncPeerPolicy(nextDevice = remoteDevice),
                peerAvailabilityRegistry = PeerAvailabilityRegistry(
                    localStack.ctx.clock,
                    MutableStateFlow(RouterConfig()),
                    FakePeerAvailabilityStore()
                ),
                maxIdlePoll = MutableStateFlow(1.seconds),
            )
            val remoteHandler = SyncHandler(
                outboundMessenger = remoteStack.outboundMessenger,
                syncPayloadProvider = DefaultSyncPayloadProvider(
                    remoteMessageRepo,
                    MutableStateFlow(RouterConfig()),
                    FakeRoomRepository(
                        mapOf(roomId to listOf(localAccount, remoteAccount)),
                        mapOf(
                            localAccount to listOf(localDevice),
                            remoteAccount to listOf(remoteDevice),
                        ),
                    ),
                    remoteStack.identity,
                ),
                pendingSyncRepository = FakePendingSyncRepository(),
                systemSender = remoteStack.systemSender,
            )

            pipeline.start(scope)
            coordinator.start(scope)
            retryProcessor.runIn(scope)
            // The remote responder routes through the outbox; its dispatch loop must run
            // (production always runs it via router.start()).
            remoteStack.outboxProcessor.runIn(scope)

            // Ping triggers a frontier sync request for the unknown tip m4.
            coordinator.requestFrontierSync(roomId, listOf(m4.messageId))
            withTimeout(10.seconds) { awaitPendingSyncCount(1) }
            assertEquals(1, pendingRepo.all().size)
            val sync = pendingRepo.all().single()
            assertEquals(m4.messageId, sync.targetMessageId)

            localStack.tor.awaitSendCount(1)
            val syncRequest =
                SystemEnvelope.decode(localStack.tor.sends.single().second.payload)
                    .decodePayload() as SystemPayload.SyncRequest
            assertEquals(listOf(m4.messageId), syncRequest.missingIds)
            assertEquals(listOf(m1.messageId), syncRequest.knownIds)

            remoteHandler.onSyncRequested(syncRequest, sourceDevice = localDevice)
            withTimeout(10.seconds) { remoteStack.tor.awaitSendCount(3) }

            val returned = remoteStack.tor.sends.map { (_, bin) ->
                MessageEnvelope.decode(bin.payload).decodePayload()
            }
            assertTrue(returned.any { it.messageId == m2.messageId })
            assertTrue(returned.any { it.messageId == m3.messageId })
            assertTrue(returned.any { it.messageId == m4.messageId })

            returned.forEach { router.emitIncoming(it) }
            // Await the stable end state, not a single observation: responses can arrive
            // out of order (tip first), in which case the fulfilled row is deleted and a
            // new gap row is briefly created before the ancestry lands. The state must
            // hold for several consecutive checks — any single read can race a concurrent
            // transition, but a sustained observation cannot outlive the three ingested
            // responses that are the only row writers here. The timeout backstop still
            // fails loudly if convergence genuinely never happens.
            // Generous budget: under parallel-suite load the shared dispatchers are slow.
            val history = mutableListOf<String>()
            withTimeout(30.seconds) {
                var stablePasses = 0
                while (stablePasses < 10) {
                    val pending = pendingRepo.all().map {
                        when (it.targetMessageId) {
                            m2.messageId -> "m2"
                            m3.messageId -> "m3"
                            m4.messageId -> "m4"
                            else -> "?" + it.targetMessageId.toString().takeLast(4)
                        }
                    }
                    val size = localMessageRepo.byId.size
                    val hold = localCausalHold.findByRoom(roomId).size
                    val sig = "pending=$pending size=$size hold=$hold"
                    if (history.lastOrNull() != sig) history += sig
                    if (pending.isEmpty() && size == 5 && hold == 0) {
                        stablePasses++
                    } else {
                        stablePasses = 0
                    }
                    delay(25.milliseconds)
                }
            }

            val leftover = pendingRepo.all()
            val holdOpen = localCausalHold.findByRoom(roomId)
            val stored = localMessageRepo.byId.size
            assertTrue(
                leftover.isEmpty(),
                "pending sync should be deleted after range filled\n" +
                        "leftover=$leftover holdOpen=$holdOpen stored=$stored\n" +
                        "history=\n" + history.joinToString("\n"),
            )
            assertTrue(localCausalHold.findByRoom(roomId).isEmpty())
            assertEquals(5, localMessageRepo.byId.size)
        } finally {
            coordinator.stop()
            scope.cancel()
        }
    }
}

/** Minimal [Router] that records outgoing sends and can emit inbound messages into the pipeline. */
private class RecordingRouter : Router {
    private val _incomingMessages = MutableSharedFlow<MessagePayload>(replay = 64, extraBufferCapacity = 64)
    override val incomingMessages: Flow<MessagePayload> = _incomingMessages.asSharedFlow()

    override val typingIndicators: Flow<TypingIndicatorEvent> = MutableSharedFlow()

    override val bootstrapPackets: Flow<BootstrapPacketEvent> = MutableSharedFlow()

    override val pingPayloads: Flow<PingFrontiers> = MutableSharedFlow()

    override val onlineAccounts: Flow<Set<AccountId>> = emptyFlow()

    val sent = mutableListOf<MessagePayload>()

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun isRunning(): Boolean = true
    override suspend fun announceOnline() = Unit

    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
    ): AccountPushReport {
        sent.add(payload)
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
}
