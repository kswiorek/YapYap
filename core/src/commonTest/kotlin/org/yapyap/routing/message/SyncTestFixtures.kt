package org.yapyap.routing.message


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.PendingSync
import org.yapyap.orchestrator.store.PendingSyncRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SyncId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.message.port.SyncPayloadProvider
import org.yapyap.routing.outbound.EnvelopeDispatcher
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.presence.FakePeerAvailabilityStore
import org.yapyap.routing.presence.PeerAvailabilityRegistry
import org.yapyap.routing.router.AccountPushReport
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.MessageAck
import org.yapyap.routing.router.MessageCarrier
import org.yapyap.routing.router.PassthroughFakeEnvelopeProtectionService
import org.yapyap.routing.router.PingFrontiers
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.TypingIndicatorEvent
import org.yapyap.routing.router.testRoutingContext
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.NoopMessageCarrier
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** [InboundMessagePipeline] whose [ingestResults] can be driven manually. */
internal class FakeInboundMessagePipeline : InboundMessagePipeline {
    private val _ingestResults = MutableSharedFlow<IngestResult>(extraBufferCapacity = 64)
    override val ingestResults: Flow<IngestResult> = _ingestResults.asSharedFlow()

    fun emit(result: IngestResult): Boolean = _ingestResults.tryEmit(result)

    override fun start(scope: CoroutineScope) = Unit
}

/**
 * In-memory [PendingSyncRepository].
 *
 * [frontierOf] recomputes the requester's chainable frontier fresh on every
 * [buildSyncRequest], mirroring the real repository (which never stores knownIds).
 */
internal class FakePendingSyncRepository(
    private val frontierOf: suspend (RoomId) -> List<MessageId> = { emptyList() },
) : PendingSyncRepository {
    private val rows = linkedMapOf<SyncId, PendingSync>()
    private val mutex = Mutex()

    // Test-only concurrency: multithreaded tests (Dispatchers.Default
    // background loops racing the test thread) must not CME on iteration.
    // (Mutex import kept for the withLock calls below.)

    override suspend fun insertSync(
        syncId: SyncId,
        roomId: RoomId,
        targetMessageId: MessageId,
        candidateAccounts: List<AccountId>,
    ) = mutex.withLock {
        if (rows.values.none { it.roomId == roomId && it.targetMessageId == targetMessageId }) {
            rows[syncId] = PendingSync(syncId, roomId, targetMessageId, candidateAccounts.distinct())
        }
    }

    override suspend fun deleteSync(syncId: SyncId) {
        mutex.withLock { rows.remove(syncId) }
    }

    override suspend fun deleteSyncsByTarget(roomId: RoomId, targetMessageId: MessageId) {
        mutex.withLock {
            rows.entries.removeAll { (_, row) -> row.roomId == roomId && row.targetMessageId == targetMessageId }
        }
    }

    override suspend fun buildSyncRequest(syncId: SyncId): SyncPayload.Request? {
        // Snapshot under lock, then build outside it: frontierOf is caller
        // code and must never run under this mutex (non-reentrant).
        val row = mutex.withLock { rows[syncId] } ?: return null
        return SyncPayload.Request(
            roomId = row.roomId,
            syncId = syncId,
            missingIds = listOf(row.targetMessageId),
            knownIds = frontierOf(row.roomId),
        )
    }

    override suspend fun candidateAccounts(syncId: SyncId): List<AccountId> =
        mutex.withLock { rows[syncId]?.candidateAccounts.orEmpty() }

    override suspend fun syncIdsWithCandidate(accountId: AccountId): List<SyncId> =
        mutex.withLock { rows.values.filter { accountId in it.candidateAccounts }.map { it.syncId } }

    override suspend fun syncIdsInRoom(roomId: RoomId): List<SyncId> =
        mutex.withLock { rows.values.filter { it.roomId == roomId }.map { it.syncId } }

    override suspend fun allSyncIds(): List<SyncId> = mutex.withLock { rows.keys.toList() }

    override suspend fun addCandidateAccounts(syncId: SyncId, accountIds: List<AccountId>) {
        mutex.withLock {
            rows[syncId]?.let {
                rows[syncId] = it.copy(candidateAccounts = (it.candidateAccounts + accountIds).distinct())
            }
        }
    }

    override suspend fun appendCandidateAccountsForRoom(roomId: RoomId, accountIds: List<AccountId>) =
        mutex.withLock {
            rows.values.filter { it.roomId == roomId }.forEach { row ->
                rows[row.syncId] = row.copy(candidateAccounts = (row.candidateAccounts + accountIds).distinct())
            }
        }

    override suspend fun findSyncByTarget(roomId: RoomId, targetMessageId: MessageId): PendingSync? =
        mutex.withLock { rows.values.firstOrNull { it.roomId == roomId && it.targetMessageId == targetMessageId } }

    suspend fun all(): List<PendingSync> = mutex.withLock { rows.values.toList() }
}

/**
 * [SyncPayloadProvider] over a [FakePendingSyncRepository] (requests and candidates), serving a
 * configurable batch of messages and recording what was asked for.
 */
internal class RecordingSyncPayloadProvider(
    var messages: List<DagPayload> = emptyList(),
    private val pending: PendingSyncRepository = FakePendingSyncRepository(),
) : SyncPayloadProvider {
    val requests = mutableListOf<SyncPayload.Request>()
    val requesters = mutableListOf<AccountId>()

    override suspend fun syncRequest(syncId: SyncId): SyncPayload.Request? = pending.buildSyncRequest(syncId)

    override suspend fun syncCandidates(syncId: SyncId): List<AccountId> = pending.candidateAccounts(syncId)

    override suspend fun syncsWithCandidate(account: AccountId): List<SyncId> = pending.syncIdsWithCandidate(account)

    override suspend fun serveSync(request: SyncPayload.Request, requester: AccountId): List<DagPayload> {
        requests.add(request)
        requesters.add(requester)
        return messages
    }
}

/** Router stand-in for orchestrator-side sync tests: records what the coordinator asks of it. */
internal class RecordingSyncRouter {
    val started = mutableListOf<Pair<SyncId, Instant>>()
    val reopened = mutableListOf<Pair<SyncId, AccountId>>()
    val sent = mutableListOf<Pair<AccountId, DagPayload>>()

    val messages: MessageCarrier = object : NoopMessageCarrier() {
        override val incomingMessages: Flow<DagPayload> = MutableSharedFlow()
        override val deliveries: Flow<MessageAck> = emptyFlow()
        override val typingIndicators: Flow<TypingIndicatorEvent> = MutableSharedFlow()
        override val frontiers: Flow<PingFrontiers> = emptyFlow()

        override suspend fun startSync(syncId: SyncId, firstAttemptAt: Instant) {
            started += syncId to firstAttemptAt
        }

        override suspend fun reopenSync(syncId: SyncId, account: AccountId) {
            reopened += syncId to account
        }

        override suspend fun sendMessage(target: AccountId, payload: DagPayload): AccountPushReport {
            sent += target to payload
            return super.sendMessage(target, payload)
        }
    }
}


/** [SyncPeerPolicy] that always returns a fixed device (or null when not set). */
internal class FixedSyncPeerPolicy(
    var nextDevice: PeerId? = null,
) : SyncPeerPolicy {
    override fun pickNextDevice(candidates: List<PeerId>, declined: Set<PeerId>, unanswered: Set<PeerId>): PeerId? =
        nextDevice
}

/** Minimal [DeviceIdentity] — signing/encryption keys are not validated here. */
internal fun testDeviceIdentity(deviceId: PeerId): DeviceIdentity =
    DeviceIdentity(
        deviceId = deviceId,
        signingKey = SigningPublicKey(byteArrayOf(1)),
        encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
    )

/** Wires a real router-internal send path against recording transports + in-memory outbox. */
internal class SyncRoutingStack(
    val tor: RecordingTorTransport,
    val webRtc: RecordingWebRtcTransport,
    val identity: FakeIdentityResolverForRouter,
    val ctx: RoutingContext,
    val outbox: TrackingPacketOutbox,
    val outboxProcessor: OutboxProcessor,
    val outboundMessenger: OutboundMessenger,
    val systemSender: SystemSender,
    val torLinkKeeper: LinkKeeper,
    val webRtcLinkKeeper: LinkKeeper,
)

internal fun buildSyncRoutingStack(
    localDevice: DeviceIdentity,
    peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    clock: FakeClock = FakeClock(epochSeconds(10_000L)),
): SyncRoutingStack {
    val tor = RecordingTorTransport()
    val webRtc = RecordingWebRtcTransport()
    val identity = FakeIdentityResolverForRouter(localDevice, peersByAccount, torByPeer)
    val routerConfig = RouterConfig()
    val ctx = testRoutingContext(
        identity = identity,
        tor = tor,
        webRtc = webRtc,
        clock = clock,
        routerConfig = routerConfig,
        protections = PassthroughFakeEnvelopeProtectionService(),
    )

    val dispatcher = EnvelopeDispatcher(ctx)
    val policy = LinkPreferencePolicy(MutableStateFlow(routerConfig))
    val outbox = TrackingPacketOutbox()
    val availabilityRegistry =
        PeerAvailabilityRegistry(clock, MutableStateFlow(routerConfig), FakePeerAvailabilityStore())
    val torLinkKeeper = LinkKeeper(
        name = "Tor-test",
        sessions = ctx.torPeers,
        targets = { emptyList() },
        ready = flowOf(false),
        config = MutableStateFlow(routerConfig.torLinkKeeperConfig),
        clock = clock,
    )
    val webRtcLinkKeeper = LinkKeeper(
        name = "WebRTC-test",
        sessions = webRtc,
        targets = { emptyList() },
        ready = flowOf(false),
        config = MutableStateFlow(routerConfig.webrtcLinkKeeperConfig),
        clock = clock,
    )
    val outboxProcessor = OutboxProcessor(
        ctx,
        dispatcher,
        policy,
        outbox,
        maxIdlePoll = MutableStateFlow(60.seconds),
        peerAvailabilityRegistry = availabilityRegistry,
        linkKeeper = torLinkKeeper,
        relaySelection = NoRelaySelection,
    )
    val outboundMessenger = OutboundMessenger(
        ctx,
        outboxProcessor = outboxProcessor,
        webRtcLinks = webRtcLinkKeeper,
    )
    val dedup = InMemoryPacketDeduplicator()
    val systemSender = SystemSender(ctx, policy, dispatcher, dedup)
    return SyncRoutingStack(
        tor = tor,
        webRtc = webRtc,
        identity = identity,
        ctx = ctx,
        outbox = outbox,
        outboxProcessor = outboxProcessor,
        outboundMessenger = outboundMessenger,
        systemSender = systemSender,
        torLinkKeeper = torLinkKeeper,
        webRtcLinkKeeper = webRtcLinkKeeper,
    )
}
