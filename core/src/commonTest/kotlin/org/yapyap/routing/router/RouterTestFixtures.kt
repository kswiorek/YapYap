package org.yapyap.routing.router

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.*
import org.yapyap.crypto.e2ee.manager.DefaultCryptoSessionManager
import org.yapyap.crypto.e2ee.session.X3dhHandshake
import org.yapyap.crypto.e2ee.session.X3dhRemotePeerKeys
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.key.InMemoryOpkRepository
import org.yapyap.persistence.packet.OutboxEntry
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.persistence.sync.PendingSyncRow
import org.yapyap.protection.PassthroughFileProtection
import org.yapyap.protection.envelope.*
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.*
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.ping.LamportSnapshotProvider
import org.yapyap.routing.policy.SessionOrTorPolicy
import org.yapyap.routing.sync.SyncPayloadProvider
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSignal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class PassthroughFakeEnvelopeProtectionService : EnvelopeProtectionService {

    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        WebRtcSignalEnvelope(
            signalEnvelopeId = Uuid.random(),
            kind = input.kind,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            nonce = ByteArray(context.securityScheme.nonceSize) { 1 },
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.payload,
        )

    override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        WebRtcSignal(
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = envelope.payload,
        )

    override suspend fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        error("not used in router transport tests")

    override suspend fun openFile(envelope: FileEnvelope): OpenedFileEnvelope =
        error("not used in router transport tests")

    override suspend fun decryptFileChunk(chunk: FilePayload.EncryptedChunk): FileChunk =
        error("not used in router transport tests")

    override suspend fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        val messageId =
            when (input) {
                is MessagePayload.Text -> input.messageId
                is MessagePayload.GlobalEvent -> input.messageId
            }
        return MessageEnvelope(
            messageEnvelopeId = messageId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            nonce = ByteArray(context.securityScheme.nonceSize) { 1 },
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.encode(),
        )
    }

    override suspend fun openMessage(envelope: MessageEnvelope): MessagePayload = envelope.decodePayload()

    override suspend fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope {
        return SystemEnvelope(
            systemEnvelopeId = Uuid.random(),
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            nonce = ByteArray(context.securityScheme.nonceSize) { 1 },
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.encode(),
        )
    }

    override suspend fun openSystem(envelope: SystemEnvelope): SystemPayload = envelope.decodePayload()

    override suspend fun protectBootstrap(
        input: BootstrapIntroPayload,
        context: EnvelopeProtectContext
    ): BootstrapEnvelope =
        BootstrapEnvelope(
            bootstrapEnvelopeId = Uuid.random(),
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            payload = input.encode(),
        )

    override suspend fun openBootstrap(envelope: BootstrapEnvelope): BootstrapIntroPayload =
        BootstrapIntroPayload.decode(envelope.payload)
}

/**
 * Wraps [delegate] and tracks how many [protectMessage] calls overlap in time.
 * Used to verify multi-peer fan-out runs concurrently rather than sequentially.
 */
internal class ConcurrencyTrackingEnvelopeProtectionService(
    private val delegate: EnvelopeProtectionService,
    private val protectDelayMillis: Long = 200,
) : EnvelopeProtectionService {
    private val protectStatsMutex = Mutex()
    private var activeProtects = 0
    var maxConcurrentProtects = 0
        private set

    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        delegate.protectSignal(input, context)

    override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        delegate.openSignal(envelope)

    override suspend fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        delegate.protectFile(input, context)

    override suspend fun openFile(envelope: FileEnvelope): OpenedFileEnvelope =
        delegate.openFile(envelope)

    override suspend fun decryptFileChunk(chunk: FilePayload.EncryptedChunk): FileChunk =
        delegate.decryptFileChunk(chunk)

    override suspend fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        protectStatsMutex.withLock {
            activeProtects++
            if (activeProtects > maxConcurrentProtects) {
                maxConcurrentProtects = activeProtects
            }
        }
        try {
            delay(protectDelayMillis.milliseconds)
            return delegate.protectMessage(input, context)
        } finally {
            protectStatsMutex.withLock {
                activeProtects--
            }
        }
    }

    override suspend fun openMessage(envelope: MessageEnvelope): MessagePayload =
        delegate.openMessage(envelope)

    override suspend fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope =
        delegate.protectSystem(input, context)

    override suspend fun openSystem(envelope: SystemEnvelope): SystemPayload =
        delegate.openSystem(envelope)

    override suspend fun protectBootstrap(
        input: BootstrapIntroPayload,
        context: EnvelopeProtectContext
    ): BootstrapEnvelope =
        delegate.protectBootstrap(input, context)

    override suspend fun openBootstrap(envelope: BootstrapEnvelope): BootstrapIntroPayload =
        delegate.openBootstrap(envelope)
}

internal class FakeSyncPayloadProvider : SyncPayloadProvider{
    override suspend fun getMessages(syncRequest: SystemPayload.SyncRequest): List<MessagePayload> {
        error("not used in router transport tests")
    }

}

internal class InMemoryPendingSyncRepository : PendingSyncRepository {
    private val rows = mutableMapOf<Uuid, PendingSyncRow>()
    private val nextAttempts = mutableMapOf<Uuid, Instant>()

    override suspend fun insertSync(
        syncId: Uuid,
        roomId: RoomId,
        anchorLamport: Long,
        orphanLamport: Long,
        candidateAccounts: List<AccountId>,
        nextAttemptAt: Instant,
    ) {
        rows[syncId] = PendingSyncRow(
            syncId = syncId,
            roomId = roomId,
            anchorLamport = anchorLamport,
            orphanLamport = orphanLamport,
            candidateAccounts = candidateAccounts,
            attemptedDevices = emptySet(),
            attempts = 0,
        )
        nextAttempts[syncId] = nextAttemptAt
    }

    override suspend fun updateOrphanLamport(syncId: Uuid, orphanLamport: Long) {
        rows[syncId]?.let { rows[syncId] = it.copy(orphanLamport = orphanLamport) }
    }

    override suspend fun deleteSync(syncId: Uuid) {
        rows.remove(syncId)
        nextAttempts.remove(syncId)
    }

    override suspend fun earliestDueAt(): Instant? =
        nextAttempts.values.minOrNull()

    override suspend fun findDue(now: Instant, limit: Int): List<PendingSyncRow> =
        rows.values.toList().take(limit)

    override suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Instant) {
        rows[syncId]?.let { rows[syncId] = it.copy(attempts = it.attempts + 1) }
        nextAttempts[syncId] = nextAttemptAt
    }

    override suspend fun getAttemptedDevices(syncId: Uuid): Set<PeerId> =
        rows[syncId]?.attemptedDevices ?: emptySet()

    override suspend fun accelerateForOnlinePeer(deviceId: PeerId, at: Instant) = Unit

    override suspend fun updateAttemptAt(syncId: Uuid, nextAttemptAt: Instant) {
        nextAttempts[syncId] = nextAttemptAt
    }

    override suspend fun addAttemptedPeer(syncId: Uuid, deviceId: PeerId) {
        rows[syncId]?.let { rows[syncId] = it.copy(attemptedDevices = it.attemptedDevices + deviceId) }
    }

    override suspend fun findGapSyncByAnchor(roomId: RoomId, anchorLamport: Long): PendingSyncRow? =
        rows.values.firstOrNull { it.roomId == roomId && it.anchorLamport == anchorLamport }
}


internal class InMemoryPacketDeduplicator : PacketDeduplicator {
    private val seen = mutableSetOf<Pair<PeerId, Uuid>>()
    private val nackReasons = mutableMapOf<Pair<PeerId, Uuid>, PacketNackReason>()

    override suspend fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAt: Instant): Boolean {
        val key = sourceDeviceId to packetId
        return seen.add(key)
    }

    override suspend fun clearPacket(
        packetId: Uuid,
        sourceDeviceId: PeerId
    ) {
        seen.remove(sourceDeviceId to packetId)
    }

    override suspend fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        nackReasons[sourceDeviceId to packetId] = nackReason
    }

    override suspend fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason? =
        nackReasons[sourceDeviceId to packetId]

    override suspend fun prune(receivedBefore: Instant) {
        // No-op for router contract tests
    }
}

internal class RecordingPacketDeduplicator(private val delegate: PacketDeduplicator) : PacketDeduplicator {
    val firstSeenCalls = mutableListOf<Triple<Uuid, PeerId, Instant>>()
    val firstSeenResults = mutableListOf<Boolean>()
    val markNackedCalls = mutableListOf<Triple<Uuid, PeerId, PacketNackReason>>()

    override suspend fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAt: Instant): Boolean {
        firstSeenCalls.add(Triple(packetId, sourceDeviceId, receivedAt))
        val result = delegate.firstSeen(packetId, sourceDeviceId, receivedAt)
        firstSeenResults.add(result)
        return result
    }

    override suspend fun clearPacket(
        packetId: Uuid,
        sourceDeviceId: PeerId
    ) {
        delegate.clearPacket(
            packetId = packetId,
            sourceDeviceId = sourceDeviceId,
        )
    }

    override suspend fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        markNackedCalls.add(Triple(packetId, sourceDeviceId, nackReason))
        delegate.markNacked(packetId, sourceDeviceId, nackReason)
    }

    override suspend fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason? =
        delegate.getNackReason(packetId, sourceDeviceId)

    override suspend fun prune(receivedBefore: Instant) {
        delegate.prune(receivedBefore)
    }
}

internal class FakeIdentityResolverForRouter(
    private val localDevice: DeviceIdentityRecord,
    private val peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    private val torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    val torUpdates: MutableList<Pair<PeerId, TorEndpoint>> = mutableListOf(),
) : IdentityResolver {

    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = localDevice

    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord =
        error("FakeIdentityResolverForRouter: account record not stubbed")

    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun getLocalDeviceId(): PeerId  = error("not used")
    override suspend fun getLocalAccountId(): AccountId = error("not used in test")

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord =
        throw CryptoException.MissingDeviceRecord(deviceId.id)

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint =
        torByPeer[deviceId] ?: TorEndpoint(onionAddress = "missing.onion", port = 80)

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersByAccount[accountId].orEmpty()

    override suspend fun getAllPeers(): List<PeerId> = peersByAccount.values.flatten().distinct()

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        peersByAccount.entries.firstOrNull { deviceId in it.value }?.key

    override suspend fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torUpdates.add(deviceId to torEndpoint)
        torByPeer[deviceId] = torEndpoint
    }

    override suspend fun resolvePeerX3dhRemoteKeys(
        deviceId: PeerId,
        signedPreKeyId: String?,
    ): X3dhRemotePeerKeys = error("not used in test")

    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord =
        error("FakeIdentityResolverForRouter: signed prekey not stubbed")

    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord =
        error("FakeIdentityResolverForRouter: signed prekey not stubbed")
}

internal class TrackingPacketOutbox : PacketOutbox {
    private data class StoredEntry(
        val envelope: BinaryEnvelope,
        var nextRetryAt: Instant,
        var attempts: Long,
        val relayMessage: Boolean,
        val expiresAt: Instant,
        val blobSize: Long,
    )

    private val entries = linkedMapOf<Uuid, StoredEntry>()
    val enqueued = mutableListOf<BinaryEnvelope>()
    val markDeliveredCalls = mutableListOf<Uuid>()
    val recordAttemptCalls = mutableListOf<Triple<Uuid, Instant, Instant>>()
    val setDueForTargetCalls = mutableListOf<Pair<PeerId, Instant>>()

    override suspend fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Instant, relayMessage: Boolean) {
        val blobSize = envelope.encode().size.toLong()
        entries[envelope.packetId] = StoredEntry(
            envelope = envelope,
            nextRetryAt = nextRetryAt,
            attempts = 0,
            relayMessage = relayMessage,
            expiresAt = envelope.expiresAt,
            blobSize = blobSize,
        )
        enqueued.add(envelope)
    }

    override suspend fun markDelivered(packetId: Uuid) {
        markDeliveredCalls.add(packetId)
        entries.remove(packetId)
    }

    override suspend fun setDueForTarget(target: PeerId, nextRetryAt: Instant) {
        setDueForTargetCalls.add(target to nextRetryAt)
        for (entry in entries.values) {
            if (entry.envelope.target == target && entry.nextRetryAt > nextRetryAt) {
                entry.nextRetryAt = nextRetryAt
            }
        }
    }

    override suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Instant, at: Instant) {
        recordAttemptCalls.add(Triple(packetId, nextRetryAt, at))
        val entry = entries[packetId] ?: return
        entry.attempts += 1
        entry.nextRetryAt = nextRetryAt
    }

    override suspend fun listAllForTarget(target: PeerId): List<OutboxEntry> =
        entries.values
            .filter { it.envelope.target == target }
            .map { it.toOutboxEntry() }

    override suspend fun listDue(now: Instant): List<OutboxEntry> =
        entries.values
            .filter { it.nextRetryAt <= now }
            .map { it.toOutboxEntry() }

    override suspend fun pruneExpired(now: Instant): Int {
        val expiredKeys = entries.filterValues { it.expiresAt <= now }.keys
        expiredKeys.forEach { entries.remove(it) }
        return expiredKeys.size
    }

    override suspend fun earliestPendingRetryAt(): Instant? =
        entries.values.minOfOrNull { it.nextRetryAt }

    override suspend fun relayCacheBytes(): Long =
        entries.values.filter { it.relayMessage }.sumOf { it.blobSize }

    override suspend fun pruneRelayOverCapacity(maxBytes: Long): Int {
        var evicted = 0
        while (relayCacheBytes() > maxBytes) {
            val victim = entries.values
                .filter { it.relayMessage }
                .minWithOrNull(compareBy<StoredEntry> { it.expiresAt }.thenBy { it.envelope.packetId })
                ?: break
            entries.remove(victim.envelope.packetId)
            evicted++
        }
        return evicted
    }

    fun contains(packetId: Uuid): Boolean = entries.containsKey(packetId)

    fun getNextRetryAt(packetId: Uuid): Instant? = entries[packetId]?.nextRetryAt

    fun getAttempts(packetId: Uuid): Long = entries[packetId]?.attempts ?: 0L

    private fun StoredEntry.toOutboxEntry(): OutboxEntry =
        OutboxEntry(
            packetId = envelope.packetId,
            envelope = envelope,
            nextRetryAt = nextRetryAt,
            attempts = attempts,
        )
}

internal class E2eeIdentityResolverForRouter(
    private val local: TestPeerIdentity,
    private val peers: Map<PeerId, TestPeerIdentity>,
    private val peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    private val torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    val torUpdates: MutableList<Pair<PeerId, TorEndpoint>> = mutableListOf(),
    private val crypto: CryptoProvider = DefaultCryptoProvider(),
) : IdentityResolver {

    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = local.device

    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord =
        error("E2eeIdentityResolverForRouter: account record not stubbed")

    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        when (purpose) {
            IdentityKeyPurpose.SIGNING -> local.signingPrivateKey
            IdentityKeyPurpose.ENCRYPTION -> local.encryptionPrivateKey
            else -> error("unexpected purpose $purpose")
        }

    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        error("E2eeIdentityResolverForRouter: account private key not stubbed")

    override suspend fun getLocalDeviceId(): PeerId = local.device.deviceId
    override suspend fun getLocalAccountId(): AccountId = error("not used in test")

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord =
        peers[deviceId]?.device ?: throw CryptoException.MissingDeviceRecord(deviceId.id)

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint =
        torByPeer[deviceId] ?: TorEndpoint(onionAddress = "missing.onion", port = 80)

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersByAccount[accountId].orEmpty()

    override suspend fun getAllPeers(): List<PeerId> = peers.keys.toList()

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        peersByAccount.entries.firstOrNull { deviceId in it.value }?.key

    override suspend fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torUpdates.add(deviceId to torEndpoint)
        torByPeer[deviceId] = torEndpoint
    }

    override suspend fun resolvePeerX3dhRemoteKeys(
        deviceId: PeerId,
        signedPreKeyId: String?,
    ): X3dhRemotePeerKeys {
        val device = resolvePeerIdentityRecord(deviceId)
        val signedPreKey = when {
            signedPreKeyId != null -> {
                device.signedPreKey?.takeIf { it.keyId == signedPreKeyId }
                    ?: error("Signed prekey not found: $signedPreKeyId")
            }
            else -> device.signedPreKey
                ?: error("Missing signed prekey on roster for deviceId=$deviceId")
        }
        require(crypto.verifyDetached(device.signing.publicKey, signedPreKey.publicKey, signedPreKey.signature)) {
            "failed to verify signed prekey signature"
        }
        return X3dhRemotePeerKeys(
            identityEncryptionPublicKey = device.encryption.publicKey,
            signedPreKeyPublicKey = signedPreKey.publicKey,
            signedPreKeyId = signedPreKey.keyId,
        )
    }

    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord = local.signedPreKey

    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord {
        require(signedPreKeyId == local.signedPreKey.keyId) {
            "Signed prekey not found: $signedPreKeyId"
        }
        return local.signedPreKey
    }
}

internal data class E2eeRouterTestStack(
    val peer: TestPeerIdentity,
    val identity: E2eeIdentityResolverForRouter,
    val protection: DefaultEnvelopeProtectionService,
)

internal fun buildE2eeRouterStack(
    local: TestPeerIdentity,
    remote: TestPeerIdentity,
    peersByAccount: Map<AccountId, List<PeerId>>,
    torByPeer: MutableMap<PeerId, TorEndpoint>,
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    crypto: CryptoProvider = DefaultCryptoProvider(),
    bootstrapKeySource: BootstrapKeySource = BootstrapKeySource { null },
): E2eeRouterTestStack {
    val identity = E2eeIdentityResolverForRouter(
        local = local,
        peers = mapOf(remote.device.deviceId to remote),
        peersByAccount = peersByAccount,
        torByPeer = torByPeer,
        crypto = crypto,
    )
    val sessionManager = DefaultCryptoSessionManager(
        crypto = crypto,
        x3dh = X3dhHandshake(crypto),
        sessionStore = MapBackedCryptoSessionStore(),
        identityResolver = identity,
        opkRepository = InMemoryOpkRepository(crypto),
        clock = clock,
        cryptoLimits = MutableStateFlow(testCryptoLimits()),
        sessionConfig = MutableStateFlow(
            CryptoSessionConfig())
    )
    val signatureProvider = DefaultSignatureProvider(identity, crypto)
    val protection = DefaultEnvelopeProtectionService(
        webRtcSignalProtection = SignedWebRtcSignalProtection(signatureProvider, crypto),
        fileProtection = PassthroughFileProtection(),
        messageProtection = SignedAndEncryptedMessageProtection(signatureProvider, sessionManager, crypto),
        systemProtection = SignedSystemProtection(signatureProvider, crypto),
        bootstrapProtection = BootstrapIntroProtection(crypto, bootstrapKeySource),
    )
    return E2eeRouterTestStack(
        peer = local,
        identity = identity,
        protection = protection,
    )
}

internal class FakeLamportSnapshotProvider : LamportSnapshotProvider {
    override suspend fun latestRoomLamports(peerId: PeerId): List<Pair<RoomId, Long>> = emptyList()
}

internal fun e2eeRouterUnderTest(
    stack: E2eeRouterTestStack,
    tor: RecordingTorTransport,
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    dedup: PacketDeduplicator = InMemoryPacketDeduplicator(),
    outbox: PacketOutbox = TrackingPacketOutbox(),
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    routerConfig: RouterConfig = RouterConfig(),
    syncPayloadProvider: SyncPayloadProvider = FakeSyncPayloadProvider(),
): DefaultRouter =
    DefaultRouter(
        torTransport = tor,
        webRtcTransport = webRtc,
        identityResolver = stack.identity,
        packetDeduplicator = dedup,
        packetOutbox = outbox,
        envelopeProtectionService = stack.protection,
        clock = clock,
        routerConfig = MutableStateFlow(routerConfig),
        transportLimits = MutableStateFlow(testTransportLimits()),
        syncRepository = InMemoryPendingSyncRepository(),
        syncPayloadProvider = syncPayloadProvider,
        lamportSnapshotProvider = FakeLamportSnapshotProvider(),
        peerAvailabilityStore = FakePeerAvailabilityStore(),
    )

internal fun outboxProcessorUnderTest(
    tor: TorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    outbox: PacketOutbox = TrackingPacketOutbox(),
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    routerConfig: RouterConfig = RouterConfig(),
): OutboxProcessor {
    val ctx =
        RoutingContext(
            identityResolver = identity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
            torTransport = tor,
            webRtcTransport = webRtc,
            clock = clock,
            routerConfig = MutableStateFlow(routerConfig),
            transportLimits = MutableStateFlow(testTransportLimits()),
        )
    return OutboxProcessor(
        ctx = ctx,
        dispatcher = EnvelopeDispatcher(ctx),
        transportPolicy = SessionOrTorPolicy(MutableStateFlow(routerConfig)),
        packetOutbox = outbox,
        maxIdlePoll = MutableStateFlow(routerConfig.retryLoopMaxIdlePoll),
    )
}

internal fun defaultRouterUnderTest(
    tor: TorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    dedup: PacketDeduplicator = InMemoryPacketDeduplicator(),
    outbox: PacketOutbox = TrackingPacketOutbox(),
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    routerConfig: RouterConfig = RouterConfig(),
    envelopeProtectionService: EnvelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
    syncPayloadProvider: SyncPayloadProvider = FakeSyncPayloadProvider(),
): DefaultRouter =
    DefaultRouter(
        torTransport = tor,
        webRtcTransport = webRtc,
        identityResolver = identity,
        packetDeduplicator = dedup,
        packetOutbox = outbox,
        envelopeProtectionService = envelopeProtectionService,
        clock = clock,
        routerConfig = MutableStateFlow(routerConfig),
        transportLimits = MutableStateFlow(testTransportLimits()),
        syncRepository = InMemoryPendingSyncRepository(),
        syncPayloadProvider = syncPayloadProvider,
        lamportSnapshotProvider = FakeLamportSnapshotProvider(),
        peerAvailabilityStore = FakePeerAvailabilityStore(),
    )
