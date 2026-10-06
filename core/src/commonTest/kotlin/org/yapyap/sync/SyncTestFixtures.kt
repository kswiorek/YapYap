package org.yapyap.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.persistence.sync.PendingSyncRow
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.outbound.OutboundMessenger
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.policy.DefaultRelaySelectionPolicy
import org.yapyap.routing.policy.SessionOrTorPolicy
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.*
import org.yapyap.routing.sync.SyncPayloadProvider
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** [InboundMessagePipeline] whose [ingestResults] can be driven manually. */
class FakeInboundMessagePipeline : InboundMessagePipeline {
    private val _ingestResults = MutableSharedFlow<IngestResult>(extraBufferCapacity = 64)
    override val ingestResults: Flow<IngestResult> = _ingestResults.asSharedFlow()

    fun emit(result: IngestResult): Boolean = _ingestResults.tryEmit(result)

    override fun start(scope: CoroutineScope) = Unit
}

/**
 * In-memory [PendingSyncRepository] that faithfully tracks [nextAttemptAt], unlike the
 * simpler [org.yapyap.routing.router.InMemoryPendingSyncRepository] used elsewhere.
 *
 * [frontierOf] recomputes the requester's chainable frontier fresh on every
 * [buildSyncRequest], mirroring the real repository (which never stores knownIds).
 */
class FakePendingSyncRepository(
    private val frontierOf: suspend (RoomId) -> List<Uuid> = { emptyList() },
    /**
     * Candidate-account → device resolution backing the re-open gate below.
     * Mirrors the real SQL's `devices` join (which additionally filters
     * `status != 'BANNED'` — the fake has no device status, so test devices
     * are all ACTIVE by construction).
     */
    private val devicesByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
) : PendingSyncRepository {
    private class Entry(
        var row: PendingSyncRow,
        var nextAttemptAt: Instant,
    )

    private val entries = mutableMapOf<Uuid, Entry>()

    // Test-only concurrency: multithreaded tests (Dispatchers.Default
    // background loops racing the test thread) must not CME on iteration.
    private val mutex = Mutex()

    override suspend fun insertSync(
        syncId: Uuid,
        roomId: RoomId,
        targetMessageId: Uuid,
        candidateAccounts: List<AccountId>,
        nextAttemptAt: Instant,
    ) = mutex.withLock {
        entries[syncId] = Entry(
            PendingSyncRow(
                syncId = syncId,
                roomId = roomId,
                targetMessageId = targetMessageId,
                candidateAccounts = candidateAccounts,
                attemptedDevices = emptySet(),
                attempts = 0,
            ),
            nextAttemptAt = nextAttemptAt,
        )
    }

    override suspend fun deleteSync(syncId: Uuid) {
        mutex.withLock {
            entries.remove(syncId)
        }
    }

    override suspend fun deleteSyncsByTarget(roomId: RoomId, targetMessageId: Uuid) {
        mutex.withLock {
            entries.entries.removeAll { (_, entry) ->
                entry.row.roomId == roomId && entry.row.targetMessageId == targetMessageId
            }
        }
    }

    override suspend fun buildSyncRequest(syncId: Uuid): SystemPayload.SyncRequest? {
        // Snapshot under lock, then build outside it: frontierOf is caller
        // code and must never run under this mutex (non-reentrant).
        val (roomId, target) = mutex.withLock {
            val entry = entries[syncId] ?: return@withLock null
            entry.row.roomId to entry.row.targetMessageId
        } ?: return null
        return SystemPayload.SyncRequest(
            roomId = roomId,
            syncId = syncId,
            missingIds = listOf(target),
            knownIds = frontierOf(roomId),
        )
    }

    override suspend fun earliestDueAt(): Instant? = mutex.withLock {
        entries.values.minOfOrNull { it.nextAttemptAt }
    }

    override suspend fun findDue(now: Instant, limit: Int): List<PendingSyncRow> = mutex.withLock {
        entries.values
            .filter { it.nextAttemptAt <= now }
            .sortedBy { it.nextAttemptAt }
            .take(limit)
            .map { it.row }
    }

    override suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Instant) {
        mutex.withLock {
            entries[syncId]?.let {
                it.row = it.row.copy(attempts = it.row.attempts + 1)
                it.nextAttemptAt = nextAttemptAt
            }
        }
    }

    override suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId> = mutex.withLock {
        entries[syncId]?.row?.attemptedDevices ?: emptySet()
    }

    override suspend fun accelerateForOnlinePeer(deviceId: PeerId, at: Instant) = Unit

    override suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Instant) {
        mutex.withLock {
            entries[syncId]?.let { it.nextAttemptAt = nextAttemptAt }
        }
    }

    override suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId) {
        mutex.withLock {
            entries[syncId]?.let { it.row = it.row.copy(attemptedDevices = it.row.attemptedDevices + deviceId) }
        }
    }

    override suspend fun addCandidateAccounts(syncId: Uuid, accountIds: List<AccountId>) {
        mutex.withLock {
            entries[syncId]?.let { entry ->
                entry.row = entry.row.copy(
                    candidateAccounts = (entry.row.candidateAccounts + accountIds).distinct()
                )
            }
        }
    }

    override suspend fun appendCandidateAccountsForRoom(roomId: RoomId, accountIds: List<AccountId>) =
        mutex.withLock {
            entries.values
                .filter { it.row.roomId == roomId }
                .forEach { entry ->
                    entry.row = entry.row.copy(
                        candidateAccounts = (entry.row.candidateAccounts + accountIds).distinct()
                    )
                }
        }

    override suspend fun reopenAttemptedPeerForRoom(deviceId: PeerId, roomId: RoomId, localDeviceId: PeerId) =
        mutex.withLock {
            entries.values
                .filter { it.row.roomId == roomId }
                .forEach { entry ->
                    // Inertness gate (mirrors the SQL NOT EXISTS and
                    // DefaultSyncPeerPolicy's eligibility half): only re-open when
                    // no candidate device remains un-attempted, preserving
                    // mid-round rotation.
                    val unattempted = entry.row.candidateAccounts
                        .flatMap { devicesByAccount[it].orEmpty() }
                        .filter { it != localDeviceId }
                        .distinct() - entry.row.attemptedDevices
                    if (unattempted.isEmpty()) {
                        entry.row = entry.row.copy(attemptedDevices = entry.row.attemptedDevices - deviceId)
                    }
                }
        }

    override suspend fun findSyncByTarget(roomId: RoomId, targetMessageId: Uuid): PendingSyncRow? =
        mutex.withLock {
            entries.values.firstOrNull {
                it.row.roomId == roomId && it.row.targetMessageId == targetMessageId
            }?.row
        }

    suspend fun all(): List<PendingSyncRow> = mutex.withLock { entries.values.map { it.row } }

    suspend fun nextAttemptAtOf(syncId: Uuid): Instant? = mutex.withLock { entries[syncId]?.nextAttemptAt }
}

/** Records sync requests and returns a configurable batch of messages. */
class RecordingSyncPayloadProvider(
    var messages: List<MessagePayload> = emptyList(),
    var removalNode: MessagePayload? = null,
) : SyncPayloadProvider {
    val requests = mutableListOf<SystemPayload.SyncRequest>()
    val peerIds = mutableListOf<PeerId>()

    override suspend fun getMessages(
        syncRequest: SystemPayload.SyncRequest,
        peerId: PeerId,
    ): List<MessagePayload> {
        requests.add(syncRequest)
        peerIds.add(peerId)
        return messages
    }

    override suspend fun removalNodeFor(roomId: RoomId, accountId: AccountId): MessagePayload? = removalNode
}

/** [SyncPeerPolicy] that always returns a fixed device (or null when not set). */
class FixedSyncPeerPolicy(
    var nextDevice: PeerId? = null,
) : SyncPeerPolicy {
    override fun pickNextDevice(candidates: List<PeerId>, attempted: Set<PeerId>): PeerId? = nextDevice
}

/** Minimal [DeviceIdentityRecord] — signing/encryption keys are not validated here. */
fun testDeviceIdentity(deviceId: PeerId): DeviceIdentityRecord =
    DeviceIdentityRecord(
        deviceId = deviceId,
        signing = IdentityPublicKeyRecord("signing", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
        encryption = IdentityPublicKeyRecord("encryption", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
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
    localDevice: DeviceIdentityRecord,
    peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    clock: FakeClock = FakeClock(epochSeconds(10_000L)),
): SyncRoutingStack {
    val tor = RecordingTorTransport()
    val webRtc = RecordingWebRtcTransport()
    val identity = FakeIdentityResolverForRouter(localDevice, peersByAccount)
    val ctx = RoutingContext(
        identityResolver = identity,
        packetDeduplicator = InMemoryPacketDeduplicator(),
        envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
        torTransport = tor,
        webRtcTransport = webRtc,
        clock = clock,
        routerConfig = MutableStateFlow(RouterConfig()),
        transportLimits = MutableStateFlow(testTransportLimits()),
    )
    ctx.localDeviceIdentity = localDevice

    val dispatcher = EnvelopeDispatcher(ctx)
    val policy = SessionOrTorPolicy(MutableStateFlow(RouterConfig()))
    val outbox = TrackingPacketOutbox()
    val outboxProcessor = OutboxProcessor(ctx, dispatcher, policy, outbox, maxIdlePoll = MutableStateFlow(60.seconds))
    val availabilityRegistry =
        PeerAvailabilityRegistry(clock, MutableStateFlow(RouterConfig()), FakePeerAvailabilityStore())
    val proactiveSessionOpener = ProactiveSessionOpener(ctx, availabilityRegistry)
    val relaySelectionPolicy = DefaultRelaySelectionPolicy(ctx, availabilityRegistry, MutableStateFlow(RouterConfig()))
    val outboundMessenger = OutboundMessenger(
        ctx, dispatcher, policy, outboxProcessor,
        sessionOpener = proactiveSessionOpener,
        relaySelectionPolicy = relaySelectionPolicy,
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
