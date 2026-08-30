package org.yapyap.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.DefaultDagEngine
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.pipeline.DefaultInboundMessagePipeline
import org.yapyap.orchestrator.sync.DefaultSyncCoordinator
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.router.*
import org.yapyap.routing.sync.DefaultSyncPayloadProvider
import org.yapyap.routing.sync.SyncHandler
import org.yapyap.routing.sync.SyncRetryProcessor
import org.yapyap.testfixtures.*
import org.yapyap.time.FixedEpochProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
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

    private val localTime = FixedEpochProvider(now)

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
        timeProvider = localTime,
    )
    private val router = RecordingRouter()
    private val pipeline = DefaultInboundMessagePipeline(router, dagEngine)
    private val pendingRepo = FakePendingSyncRepository()
    private val coordinator = DefaultSyncCoordinator(
        pipeline = pipeline,
        roomRepository = localRoomRepo,
        messageRepository = localMessageRepo,
        identityResolver = localIdentity,
        pendingSyncRepository = pendingRepo,
        timeProvider = localTime,
        orchestratorConfig = MutableStateFlow(OrchestratorConfig(syncGracePeriodSeconds = 0)),
    )

    private fun textMsg(
        messageId: Uuid = Uuid.random(),
        lamport: Long,
        prevId: Uuid?,
    ): MessagePayload.Text =
        MessagePayload.Text(
            messageId = messageId,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDevice,
            authorSignature = byteArrayOf(1),
            prevId = prevId,
            lamportClock = lamport,
            createdAtEpochSeconds = 0L,
            text = "m$lamport",
        )

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
            // Local already has the anchor message at lamport 0.
            val anchor = textMsg(lamport = 0, prevId = null)
            localMessageRepo.insert(anchor, isOrphaned = false)

            // Remote has the full chain 0..2; local only has 0. msg2 is the orphan on local.
            val remoteMessageRepo = FakeMessageRepository()
            val m1 = textMsg(lamport = 1, prevId = anchor.messageId)
            val m2 = textMsg(lamport = 2, prevId = m1.messageId)
            remoteMessageRepo.insert(anchor, isOrphaned = false)
            remoteMessageRepo.insert(m1, isOrphaned = false)
            remoteMessageRepo.insert(m2, isOrphaned = false)

            val localStack = buildSyncRoutingStack(
                localDevice = testDeviceIdentity(localDevice),
                peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
                time = FixedEpochProvider(now),
            )
            val remoteStack = buildSyncRoutingStack(
                localDevice = testDeviceIdentity(remoteDevice),
                peersByAccount = mapOf(localAccount to listOf(localDevice)),
                time = FixedEpochProvider(now),
            )
            val retryProcessor = SyncRetryProcessor(
                ctx = localStack.ctx,
                pendingSyncs = pendingRepo,
                systemSender = localStack.systemSender,
                peerPolicy = FixedSyncPeerPolicy(nextDevice = remoteDevice),
                peerAvailabilityRegistry = PeerAvailabilityRegistry(localStack.ctx.timeProvider, MutableStateFlow(RouterConfig())),
                maxIdlePollSeconds = MutableStateFlow(1),
            )
            val remoteHandler = SyncHandler(
                outboundMessenger = remoteStack.outboundMessenger,
                syncPayloadProvider = DefaultSyncPayloadProvider(remoteMessageRepo, MutableStateFlow(RouterConfig())),
                pendingSyncRepository = FakePendingSyncRepository(),
                systemSender = remoteStack.systemSender,
            )

            pipeline.start(scope)
            coordinator.start(scope)
            retryProcessor.runIn(scope)

            // Ingest the orphan: pipeline emits BecameOrphan -> coordinator creates a pending sync.
            router.emitIncoming(m2)
            withTimeout(10.seconds) { awaitPendingSyncCount(1) }
            assertEquals(1, pendingRepo.all().size)
            val sync = pendingRepo.all().single()
            assertEquals(0L, sync.anchorLamport)
            assertEquals(2L, sync.orphanLamport)

            // The retry processor sends a SyncRequest to the remote device.
            localStack.tor.awaitSendCount(1)
            val sent = localStack.tor.sends.single().second
            assertEquals(org.yapyap.protocol.packet.PacketType.SYSTEM, sent.packetType)
            val syncRequest =
                SystemEnvelope.decode(sent.payload).decodePayload() as SystemPayload.SyncRequest
            assertEquals(roomId, syncRequest.roomId)
            assertEquals(sync.syncId, syncRequest.syncId)
            assertEquals(0L, syncRequest.anchorLamport)
            assertEquals(2L, syncRequest.orphanLamport)

            // Remote handles the request and sends the missing messages back.
            remoteHandler.onSyncRequested(syncRequest, sourceDevice = localDevice)
            withTimeout(10.seconds) { remoteStack.tor.awaitSendCount(2) }

            val returned = remoteStack.tor.sends.map { (_, bin) ->
                MessageEnvelope.decode(bin.payload).decodePayload()
            }
            assertTrue(returned.any { it.messageId == m1.messageId }, "missing message m1 not returned")
            assertTrue(returned.any { it.messageId == m2.messageId }, "orphan m2 not returned")

            // Relay each returned message back into the local node.
            returned.forEach { router.emitIncoming(it) }
            withTimeout(10.seconds) { awaitPendingSyncEmpty() }

            // Gap closed, sync deleted.
            assertTrue(pendingRepo.all().isEmpty(), "pending sync should be deleted after gap closure")
            assertTrue(dagEngine.openGaps(roomId).isEmpty(), "no open gaps should remain")
            assertEquals(3, localMessageRepo.byId.size)
        } finally {
            coordinator.stop()
            scope.cancel()
        }
    }

    /**
     * Range sync: local seq is behind a ping -> a pending sync is created -> the retry processor
     * sends a SyncRequest -> the remote returns all missing messages -> they are ingested back and
     * the sync is deleted.
     */
    @Test
    fun rangeSync_missingMessagesRequestedAndReceived_syncDeleted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Local has messages 0 and 1; remote has 0..4. Ping advertises lamport 4.
            val m0 = textMsg(lamport = 0, prevId = null)
            val m1 = textMsg(lamport = 1, prevId = m0.messageId)
            localMessageRepo.insert(m0, isOrphaned = false)
            localMessageRepo.insert(m1, isOrphaned = false)

            val remoteMessageRepo = FakeMessageRepository()
            val m2 = textMsg(lamport = 2, prevId = m1.messageId)
            val m3 = textMsg(lamport = 3, prevId = m2.messageId)
            val m4 = textMsg(lamport = 4, prevId = m3.messageId)
            remoteMessageRepo.insert(m0, isOrphaned = false)
            remoteMessageRepo.insert(m1, isOrphaned = false)
            remoteMessageRepo.insert(m2, isOrphaned = false)
            remoteMessageRepo.insert(m3, isOrphaned = false)
            remoteMessageRepo.insert(m4, isOrphaned = false)

            val localStack = buildSyncRoutingStack(
                localDevice = testDeviceIdentity(localDevice),
                peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
                time = FixedEpochProvider(now),
            )
            val remoteStack = buildSyncRoutingStack(
                localDevice = testDeviceIdentity(remoteDevice),
                peersByAccount = mapOf(localAccount to listOf(localDevice)),
                time = FixedEpochProvider(now),
            )
            val retryProcessor = SyncRetryProcessor(
                ctx = localStack.ctx,
                pendingSyncs = pendingRepo,
                systemSender = localStack.systemSender,
                peerPolicy = FixedSyncPeerPolicy(nextDevice = remoteDevice),
                peerAvailabilityRegistry = PeerAvailabilityRegistry(localStack.ctx.timeProvider, MutableStateFlow(RouterConfig())),
                maxIdlePollSeconds = MutableStateFlow(1),
            )
            val remoteHandler = SyncHandler(
                outboundMessenger = remoteStack.outboundMessenger,
                syncPayloadProvider = DefaultSyncPayloadProvider(remoteMessageRepo, MutableStateFlow(RouterConfig())),
                pendingSyncRepository = FakePendingSyncRepository(),
                systemSender = remoteStack.systemSender,
            )

            pipeline.start(scope)
            coordinator.start(scope)
            retryProcessor.runIn(scope)

            // Ping triggers a range sync request.
            localRoomRepo.updateLocalSeq(roomId, 1L)
            coordinator.requestRangeSync(roomId, pingLamport = 4L)
            withTimeout(10.seconds) { awaitPendingSyncCount(1) }
            assertEquals(1, pendingRepo.all().size)
            val sync = pendingRepo.all().single()
            assertEquals(1L, sync.anchorLamport)
            assertEquals(4L, sync.orphanLamport)

            localStack.tor.awaitSendCount(1)
            val syncRequest =
                SystemEnvelope.decode(localStack.tor.sends.single().second.payload)
                    .decodePayload() as SystemPayload.SyncRequest
            assertEquals(1L, syncRequest.anchorLamport)
            assertEquals(4L, syncRequest.orphanLamport)

            remoteHandler.onSyncRequested(syncRequest, sourceDevice = localDevice)
            withTimeout(10.seconds) { remoteStack.tor.awaitSendCount(3) }

            val returned = remoteStack.tor.sends.map { (_, bin) ->
                MessageEnvelope.decode(bin.payload).decodePayload()
            }
            assertTrue(returned.any { it.messageId == m2.messageId })
            assertTrue(returned.any { it.messageId == m3.messageId })
            assertTrue(returned.any { it.messageId == m4.messageId })

            returned.forEach { router.emitIncoming(it) }
            withTimeout(10.seconds) { awaitPendingSyncEmpty() }

            assertTrue(pendingRepo.all().isEmpty(), "pending sync should be deleted after range filled")
            assertTrue(dagEngine.openGaps(roomId).isEmpty())
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

    val sent = mutableListOf<MessagePayload>()

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun isRunning(): Boolean = true

    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
    ): SendMessageResult {
        sent.add(payload)
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
