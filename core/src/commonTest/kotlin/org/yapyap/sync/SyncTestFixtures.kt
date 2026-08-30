package org.yapyap.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import org.yapyap.routing.policy.SessionOrTorPolicy
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.*
import org.yapyap.routing.sync.SyncPayloadProvider
import org.yapyap.time.EpochProvider
import org.yapyap.time.FixedEpochProvider
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
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
 */
class FakePendingSyncRepository : PendingSyncRepository {
    private class Entry(
        var row: PendingSyncRow,
        var nextAttemptAt: Long,
    )

    private val entries = mutableMapOf<Uuid, Entry>()

    override suspend fun insertSync(
        syncId: Uuid,
        roomId: RoomId,
        anchorLamport: Long,
        orphanLamport: Long,
        candidateAccounts: List<AccountId>,
        nextAttemptAt: Long,
    ) {
        entries[syncId] = Entry(
            PendingSyncRow(
                syncId = syncId,
                roomId = roomId,
                anchorLamport = anchorLamport,
                orphanLamport = orphanLamport,
                candidateAccounts = candidateAccounts,
                attemptedDevices = emptySet(),
                attempts = 0,
            ),
            nextAttemptAt = nextAttemptAt,
        )
    }

    override suspend fun updateOrphanLamport(syncId: Uuid, orphanLamport: Long) {
        entries[syncId]?.let { it.row = it.row.copy(orphanLamport = orphanLamport) }
    }

    override suspend fun deleteSync(syncId: Uuid) {
        entries.remove(syncId)
    }

    override suspend fun earliestDueAt(): Long? =
        entries.values.minOfOrNull { it.nextAttemptAt }

    override suspend fun findDue(now: Long, limit: Int): List<PendingSyncRow> =
        entries.values
            .filter { it.nextAttemptAt <= now }
            .sortedBy { it.nextAttemptAt }
            .take(limit)
            .map { it.row }

    override suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Long) {
        entries[syncId]?.let {
            it.row = it.row.copy(attempts = it.row.attempts + 1)
            it.nextAttemptAt = nextAttemptAt
        }
    }

    override suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId> =
        entries[syncId]?.row?.attemptedDevices ?: emptySet()

    override suspend fun accelerateForOnlinePeer(deviceId: PeerId, now: Long) = Unit

    override suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Long) {
        entries[syncId]?.let { it.nextAttemptAt = nextAttemptAt }
    }

    override suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId) {
        entries[syncId]?.let { it.row = it.row.copy(attemptedDevices = it.row.attemptedDevices + deviceId) }
    }

    override suspend fun findGapSyncByAnchor(roomId: RoomId, anchorLamport: Long): PendingSyncRow? =
        entries.values.firstOrNull { it.row.roomId == roomId && it.row.anchorLamport == anchorLamport }?.row

    fun all(): List<PendingSyncRow> = entries.values.map { it.row }

    fun nextAttemptAtOf(syncId: Uuid): Long? = entries[syncId]?.nextAttemptAt
}

/** Records sync requests and returns a configurable batch of messages. */
class RecordingSyncPayloadProvider(
    var messages: List<MessagePayload> = emptyList(),
) : SyncPayloadProvider {
    val requests = mutableListOf<SystemPayload.SyncRequest>()

    override suspend fun getMessages(syncRequest: SystemPayload.SyncRequest): List<MessagePayload> {
        requests.add(syncRequest)
        return messages
    }
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
    time: EpochProvider = FixedEpochProvider(10_000L),
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
        timeProvider = time,
        routerConfig = MutableStateFlow(RouterConfig()),
        transportLimits = MutableStateFlow(testTransportLimits()),
    )
    ctx.localDeviceIdentity = localDevice

    val dispatcher = EnvelopeDispatcher(ctx)
    val policy = SessionOrTorPolicy(MutableStateFlow(RouterConfig()))
    val outbox = TrackingPacketOutbox()
    val outboxProcessor = OutboxProcessor(ctx, dispatcher, policy, outbox, maxIdlePollSeconds = MutableStateFlow(60))
    val proactiveSessionOpener = ProactiveSessionOpener(ctx, PeerAvailabilityRegistry(time, MutableStateFlow(RouterConfig())))
    val outboundMessenger = OutboundMessenger(ctx, dispatcher, policy, outboxProcessor, sessionOpener = proactiveSessionOpener)
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
