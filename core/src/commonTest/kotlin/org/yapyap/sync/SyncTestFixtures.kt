package org.yapyap.sync


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.PendingSync
import org.yapyap.orchestrator.store.PendingSyncRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.outbound.OutboundMessenger
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.AccountPushReport
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.PassthroughFakeEnvelopeProtectionService
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.sync.SyncPayloadProvider
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.NoopRouter
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
    private val frontierOf: suspend (RoomId) -> List<Uuid> = { emptyList() },
) : PendingSyncRepository {
    private val rows = linkedMapOf<Uuid, PendingSync>()

    // Test-only concurrency: multithreaded tests (Dispatchers.Default
    // background loops racing the test thread) must not CME on iteration.
    private val mutex = Mutex()

    override suspend fun insertSync(
        syncId: Uuid,
        roomId: RoomId,
        targetMessageId: Uuid,
        candidateAccounts: List<AccountId>,
    ) = mutex.withLock {
        if (rows.values.none { it.roomId == roomId && it.targetMessageId == targetMessageId }) {
            rows[syncId] = PendingSync(syncId, roomId, targetMessageId, candidateAccounts.distinct())
        }
    }

    override suspend fun deleteSync(syncId: Uuid) {
        mutex.withLock { rows.remove(syncId) }
    }

    override suspend fun deleteSyncsByTarget(roomId: RoomId, targetMessageId: Uuid) {
        mutex.withLock {
            rows.entries.removeAll { (_, row) -> row.roomId == roomId && row.targetMessageId == targetMessageId }
        }
    }

    override suspend fun buildSyncRequest(syncId: Uuid): SystemPayload.SyncRequest? {
        // Snapshot under lock, then build outside it: frontierOf is caller
        // code and must never run under this mutex (non-reentrant).
        val row = mutex.withLock { rows[syncId] } ?: return null
        return SystemPayload.SyncRequest(
            roomId = row.roomId,
            syncId = syncId,
            missingIds = listOf(row.targetMessageId),
            knownIds = frontierOf(row.roomId),
        )
    }

    override suspend fun candidateAccounts(syncId: Uuid): List<AccountId> =
        mutex.withLock { rows[syncId]?.candidateAccounts.orEmpty() }

    override suspend fun syncIdsWithCandidate(accountId: AccountId): List<Uuid> =
        mutex.withLock { rows.values.filter { accountId in it.candidateAccounts }.map { it.syncId } }

    override suspend fun syncIdsInRoom(roomId: RoomId): List<Uuid> =
        mutex.withLock { rows.values.filter { it.roomId == roomId }.map { it.syncId } }

    override suspend fun allSyncIds(): List<Uuid> = mutex.withLock { rows.keys.toList() }

    override suspend fun addCandidateAccounts(syncId: Uuid, accountIds: List<AccountId>) {
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

    override suspend fun findSyncByTarget(roomId: RoomId, targetMessageId: Uuid): PendingSync? =
        mutex.withLock { rows.values.firstOrNull { it.roomId == roomId && it.targetMessageId == targetMessageId } }

    suspend fun all(): List<PendingSync> = mutex.withLock { rows.values.toList() }
}

/**
 * [SyncPayloadProvider] over a [FakePendingSyncRepository] (requests and candidates), serving a
 * configurable batch of messages and recording what was asked for.
 */
internal class RecordingSyncPayloadProvider(
    var messages: List<MessagePayload> = emptyList(),
    private val pending: PendingSyncRepository = FakePendingSyncRepository(),
) : SyncPayloadProvider {
    val requests = mutableListOf<SystemPayload.SyncRequest>()
    val requesters = mutableListOf<AccountId>()

    override suspend fun syncRequest(syncId: Uuid): SystemPayload.SyncRequest? = pending.buildSyncRequest(syncId)

    override suspend fun syncCandidates(syncId: Uuid): List<AccountId> = pending.candidateAccounts(syncId)

    override suspend fun syncsWithCandidate(account: AccountId): List<Uuid> = pending.syncIdsWithCandidate(account)

    override suspend fun syncMessages(request: SystemPayload.SyncRequest, requester: AccountId): List<MessagePayload> {
        requests.add(request)
        requesters.add(requester)
        return messages
    }
}

/** Router stand-in for orchestrator-side sync tests: records what the coordinator asks of it. */
internal class RecordingSyncRouter : NoopRouter() {
    val started = mutableListOf<Pair<Uuid, Instant>>()
    val reopened = mutableListOf<Pair<Uuid, AccountId>>()
    val sent = mutableListOf<Pair<AccountId, MessagePayload>>()

    override suspend fun startSync(syncId: Uuid, firstAttemptAt: Instant) {
        started += syncId to firstAttemptAt
    }

    override suspend fun reopenSync(syncId: Uuid, account: AccountId) {
        reopened += syncId to account
    }

    override suspend fun sendMessage(target: AccountId, payload: MessagePayload): AccountPushReport {
        sent += target to payload
        return super.sendMessage(target, payload)
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
    val ctx = RoutingContext(
        localIdentity = identity,
        localDeviceId = localDevice.deviceId,
        devices = identity,
        routes = identity,
        packetDeduplicator = InMemoryPacketDeduplicator(),
        envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
        torTransport = tor,
        webRtcTransport = webRtc,
        clock = clock,
        routerConfig = MutableStateFlow(RouterConfig()),
        transportLimits = MutableStateFlow(testTransportLimits()),
    )

    val dispatcher = EnvelopeDispatcher(ctx)
    val policy = LinkPreferencePolicy(MutableStateFlow(RouterConfig()))
    val outbox = TrackingPacketOutbox()
    val outboxProcessor = OutboxProcessor(ctx, dispatcher, policy, outbox, maxIdlePoll = MutableStateFlow(60.seconds))
    val availabilityRegistry =
        PeerAvailabilityRegistry(clock, MutableStateFlow(RouterConfig()), FakePeerAvailabilityStore())
    val proactiveSessionOpener = ProactiveSessionOpener(ctx, availabilityRegistry)
    val outboundMessenger = OutboundMessenger(
        ctx,
        outboxProcessor = outboxProcessor,
        sessionOpener = proactiveSessionOpener,
    )
    val systemSender = SystemSender(ctx, policy, dispatcher)
    return SyncRoutingStack(
        tor = tor,
        webRtc = webRtc,
        identity = identity,
        ctx = ctx,
        outbox = outbox,
        outboxProcessor = outboxProcessor,
        outboundMessenger = outboundMessenger,
        systemSender = systemSender,
    )
}
