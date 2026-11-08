package org.yapyap.routing.router

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.CryptoSessionConfig
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.TestPeerIdentity
import org.yapyap.crypto.e2ee.manager.DefaultCryptoSessionManager
import org.yapyap.crypto.e2ee.session.X3dhHandshake
import org.yapyap.crypto.e2ee.testCryptoLimits
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.crypto.identity.LocalKeys
import org.yapyap.crypto.identity.LocalSignedPreKey
import org.yapyap.crypto.identity.PeerKeys
import org.yapyap.crypto.identity.PeerPreKeyBundle
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.protection.PassthroughFileProtection
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.SignedAndEncryptedMessageProtection
import org.yapyap.protection.envelope.SignedSystemProtection
import org.yapyap.protection.envelope.SignedWebRtcSignalProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.BootstrapSecurityScheme
import org.yapyap.protocol.envelopes.FileChunk
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.routing.directory.DeviceDirectory
import org.yapyap.routing.directory.DeviceRouteStore
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.ping.FrontierSnapshotProvider
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.store.OutboxEntry
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.PacketDeduplicator
import org.yapyap.routing.store.PacketOutbox
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.SyncAttempt
import org.yapyap.routing.store.SyncAttemptStore
import org.yapyap.routing.sync.SyncPayloadProvider
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
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
                is MessagePayload.RoomEvent -> input.messageId
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
        input: BootstrapPayload,
        context: EnvelopeProtectContext,
        sharedSecret: ByteArray?,
    ): BootstrapEnvelope =
        BootstrapEnvelope(
            // Passthrough plaintext (no AEAD); the scheme is unchecked by this fake.
            scheme = BootstrapSecurityScheme.ACCOUNT_SIGNED,
            bootstrapEnvelopeId = Uuid.random(),
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            payload = input.encode(),
        )

    override suspend fun openBootstrap(envelope: BootstrapEnvelope): BootstrapPayload =
        BootstrapPayload.decode(envelope.payload)

    override suspend fun isBootstrapSessionActive(): Boolean = false
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
        input: BootstrapPayload,
        context: EnvelopeProtectContext,
        sharedSecret: ByteArray?,
    ): BootstrapEnvelope =
        delegate.protectBootstrap(input, context, sharedSecret)

    override suspend fun openBootstrap(envelope: BootstrapEnvelope): BootstrapPayload =
        delegate.openBootstrap(envelope)

    override suspend fun isBootstrapSessionActive(): Boolean =
        delegate.isBootstrapSessionActive()
}

/** Router transport tests have no pending syncs: nothing to ask for, nothing served. */
internal class FakeSyncPayloadProvider : SyncPayloadProvider {
    override suspend fun syncRequest(syncId: Uuid): SystemPayload.SyncRequest? = null

    override suspend fun syncCandidates(syncId: Uuid): List<AccountId> = emptyList()

    override suspend fun syncsWithCandidate(account: AccountId): List<Uuid> = emptyList()

    override suspend fun syncMessages(request: SystemPayload.SyncRequest, requester: AccountId): List<MessagePayload> =
        emptyList()
}

/** In-memory [SyncAttemptStore], mirroring the SQL semantics. */
internal class InMemorySyncAttemptStore : SyncAttemptStore {
    private data class Row(
        var attempts: Int,
        var nextAttemptAt: Instant,
        val declined: MutableSet<PeerId> = mutableSetOf()
    )

    private val rows = linkedMapOf<Uuid, Row>()
    private val mutex = Mutex()

    override suspend fun start(syncId: Uuid, firstAttemptAt: Instant) = mutex.withLock {
        if (syncId !in rows) rows[syncId] = Row(0, firstAttemptAt)
    }

    override suspend fun drop(syncId: Uuid) {
        mutex.withLock { rows.remove(syncId) }
    }

    override suspend fun earliestDueAt(): Instant? = mutex.withLock { rows.values.minOfOrNull { it.nextAttemptAt } }

    override suspend fun findDue(now: Instant, limit: Int): List<SyncAttempt> = mutex.withLock {
        rows.filterValues { it.nextAttemptAt <= now }.entries.take(limit)
            .map { (id, row) -> SyncAttempt(id, row.attempts, row.declined.toSet()) }
    }

    override suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Instant) = mutex.withLock {
        rows[syncId]?.let { it.attempts += 1; it.nextAttemptAt = nextAttemptAt }
        Unit
    }

    override suspend fun postpone(syncId: Uuid, nextAttemptAt: Instant) = mutex.withLock {
        rows[syncId]?.nextAttemptAt = nextAttemptAt
    }

    override suspend fun advanceToNow(syncIds: Collection<Uuid>, now: Instant) = mutex.withLock {
        syncIds.forEach { id -> rows[id]?.let { if (it.nextAttemptAt > now) it.nextAttemptAt = now } }
    }

    override suspend fun declinedDevices(syncId: Uuid): Set<PeerId> =
        mutex.withLock { rows[syncId]?.declined?.toSet().orEmpty() }

    override suspend fun addDeclined(syncId: Uuid, deviceId: PeerId) = mutex.withLock {
        rows[syncId]?.declined?.add(deviceId)
        Unit
    }

    override suspend fun removeDeclined(syncId: Uuid, deviceIds: Collection<PeerId>) = mutex.withLock {
        rows[syncId]?.declined?.removeAll(deviceIds.toSet())
        Unit
    }

    fun contains(syncId: Uuid): Boolean = rows.containsKey(syncId)
    fun attemptsOf(syncId: Uuid): Int? = rows[syncId]?.attempts
    fun nextAttemptAt(syncId: Uuid): Instant? = rows[syncId]?.nextAttemptAt
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
    private val localDevice: DeviceIdentity,
    private val peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    private val torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    val torUpdates: MutableList<Pair<PeerId, TorEndpoint>> = mutableListOf(),
    val accountStatuses: MutableMap<AccountId, IdentityStatus> = mutableMapOf(),
    private val deviceStatuses: Map<PeerId, IdentityStatus> = emptyMap(),
    private val localAccount: AccountId = AccountId("local-account"),
) : LocalIdentity, LocalKeys, PeerKeys, DeviceDirectory, DeviceRouteStore {

    val localDeviceId: PeerId get() = localDevice.deviceId

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity = localDevice

    override suspend fun getLocalAccountIdentity(): AccountIdentity =
        error("FakeIdentityResolverForRouter: account record not stubbed")

    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? =
        deviceStatuses[deviceId] ?: if (isKnownDevice(deviceId)) IdentityStatus.ACTIVE else null

    private fun isKnownDevice(deviceId: PeerId): Boolean =
        deviceId == localDevice.deviceId || peersByAccount.values.any { deviceId in it }

    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? =
        accountStatuses[accountId]

    suspend fun isLocalAccountAdmin(): Boolean =
        error("FakeIdentityResolverForRouter: admin flag not stubbed")

    suspend fun isLocalAccountOwner(): Boolean =
        error("FakeIdentityResolverForRouter: owner flag not stubbed")

    override suspend fun localDeviceSigningKey(): SigningPrivateKey =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun localAccountSigningKey(): SigningPrivateKey =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun getLocalDeviceId(): PeerId = error("not used")
    override suspend fun getLocalAccountId(): AccountId = localAccount

    override suspend fun resolvePeerIdentity(deviceId: PeerId): DeviceIdentity =
        throw CryptoException.MissingDeviceRecord(deviceId.id)

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? =
        torByPeer[deviceId]

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersByAccount[accountId].orEmpty()

    suspend fun getAllPeers(): List<PeerId> = peersByAccount.values.flatten().distinct()

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        peersByAccount.entries.firstOrNull { deviceId in it.value }?.key

    override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torUpdates.add(deviceId to torEndpoint)
        torByPeer[deviceId] = torEndpoint
    }

    override suspend fun resolvePeerPreKeyBundle(deviceId: PeerId, signedPreKeyId: SignedPreKeyId?): PeerPreKeyBundle =
        error("not used in test")

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey =
        error("FakeIdentityResolverForRouter: signed prekey not stubbed")

    override suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey =
        error("FakeIdentityResolverForRouter: signed prekey not stubbed")

    override suspend fun getAllActiveDeviceIds(): List<PeerId> =
        getAllPeers().filter { getDeviceStatus(it) != IdentityStatus.BANNED }

    override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
        accountIds.flatMap { getAllPeerDevicesForAccount(it) }


    override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? = resolveTorEndpointForDevice(deviceId)
}

@OptIn(InternalCoroutinesApi::class)
internal class TrackingPacketOutbox : PacketOutbox {
    private data class StoredEntry(
        val envelope: BinaryEnvelope,
        var nextRetryAt: Instant,
        var attempts: Long,
        val relayMessage: Boolean,
        val expiresAt: Instant,
        val blobSize: Long,
        val targetEndpoint: TorEndpoint? = null,
        var relayBackup: RelayBackup = RelayBackup.NONE,
        val tag: OutboxTag? = null,
    )

    private val entries = linkedMapOf<Uuid, StoredEntry>()
    private val lock = SynchronizedObject()
    val enqueued = mutableListOf<BinaryEnvelope>()
    val removeCalls = mutableListOf<Uuid>()
    val recordAttemptCalls = mutableListOf<Triple<Uuid, Instant, Instant>>()
    val setDueForTargetCalls = mutableListOf<Pair<PeerId, Instant>>()
    override suspend fun enqueue(
        envelope: BinaryEnvelope,
        nextRetryAt: Instant,
        relayMessage: Boolean,
        targetEndpoint: TorEndpoint?,
        relayBackup: RelayBackup,
        tag: OutboxTag?,
    ) {
        val blobSize = envelope.encode().size.toLong()
        synchronized(lock) {
            entries[envelope.packetId] = StoredEntry(
                envelope = envelope,
                nextRetryAt = nextRetryAt,
                attempts = 0,
                relayMessage = relayMessage,
                expiresAt = envelope.expiresAt,
                blobSize = blobSize,
                targetEndpoint = targetEndpoint,
                relayBackup = relayBackup,
                tag = tag,
            )
        }
        enqueued.add(envelope)
    }

    fun relayBackupOf(packetId: Uuid): RelayBackup? = synchronized(lock) { entries[packetId]?.relayBackup }

    override suspend fun claimRelayBackup(packetId: Uuid): Boolean = synchronized(lock) {
        val entry = entries[packetId] ?: return@synchronized false
        if (entry.relayBackup != RelayBackup.PENDING) return@synchronized false
        entry.relayBackup = RelayBackup.DEPOSITED
        true
    }

    override suspend fun remove(packetId: Uuid): OutboxEntry? {
        removeCalls.add(packetId)
        return synchronized(lock) { entries.remove(packetId)?.toOutboxEntry() }
    }

    override suspend fun hasTagged(tag: OutboxTag): Boolean =
        synchronized(lock) { entries.values.any { it.tag == tag } }

    fun tagOf(packetId: Uuid): OutboxTag? = synchronized(lock) { entries[packetId]?.tag }

    override suspend fun setDueForTarget(target: PeerId, nextRetryAt: Instant) {
        setDueForTargetCalls.add(target to nextRetryAt)
        synchronized(lock) {
            for (entry in entries.values) {
                if (entry.envelope.target == target && entry.nextRetryAt > nextRetryAt) {
                    entry.nextRetryAt = nextRetryAt
                }
            }
        }
    }

    override suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Instant, at: Instant) {
        recordAttemptCalls.add(Triple(packetId, nextRetryAt, at))
        synchronized(lock) {
            val entry = entries[packetId] ?: return
            entry.attempts += 1
            entry.nextRetryAt = nextRetryAt
        }
    }

    val deferRetryCalls = mutableListOf<Pair<Uuid, Instant>>()

    override suspend fun deferRetry(packetId: Uuid, nextRetryAt: Instant) {
        deferRetryCalls.add(packetId to nextRetryAt)
        synchronized(lock) { entries[packetId]?.nextRetryAt = nextRetryAt }
    }

    override suspend fun listAllForTarget(target: PeerId): List<OutboxEntry> =
        synchronized(lock) {
            entries.values
                .filter { it.envelope.target == target }
                .map { it.toOutboxEntry() }
        }

    override suspend fun listDue(now: Instant): List<OutboxEntry> =
        synchronized(lock) {
            entries.values
                .filter { it.nextRetryAt <= now }
                .map { it.toOutboxEntry() }
        }

    override suspend fun pruneExpired(now: Instant): List<OutboxEntry> =
        synchronized(lock) {
            val expired = entries.filterValues { it.expiresAt <= now }
            expired.keys.forEach { entries.remove(it) }
            expired.values.map { it.toOutboxEntry() }
        }

    override suspend fun earliestPendingRetryAt(): Instant? =
        synchronized(lock) { entries.values.map { it.nextRetryAt }.minOrNull() }

    override suspend fun earliestPendingRetryAfter(after: Instant): Instant? =
        synchronized(lock) { entries.values.map { it.nextRetryAt }.filter { it > after }.minOrNull() }

    override suspend fun relayCacheBytes(): Long =
        synchronized(lock) {
            entries.values.filter { it.relayMessage }.sumOf { it.blobSize }
        }

    override suspend fun pruneRelayOverCapacity(maxBytes: Long): Int {
        var evicted = 0
        while (relayCacheBytes() > maxBytes) {
            val victim = synchronized(lock) {
                entries.values
                    .filter { it.relayMessage }
                    .minWithOrNull(compareBy<StoredEntry> { it.expiresAt }.thenBy { it.envelope.packetId })
            } ?: break
            synchronized(lock) { entries.remove(victim.envelope.packetId) }
            evicted++
        }
        return evicted
    }

    fun contains(packetId: Uuid): Boolean = synchronized(lock) { entries.containsKey(packetId) }

    fun getNextRetryAt(packetId: Uuid): Instant? = synchronized(lock) { entries[packetId]?.nextRetryAt }

    fun getAttempts(packetId: Uuid): Long = synchronized(lock) { entries[packetId]?.attempts ?: 0L }

    private fun StoredEntry.toOutboxEntry(): OutboxEntry =
        OutboxEntry(
            packetId = envelope.packetId,
            envelope = envelope,
            nextRetryAt = nextRetryAt,
            attempts = attempts,
            targetEndpoint = targetEndpoint,
            tag = tag,
        )
}

internal class E2eeIdentityResolverForRouter(
    private val local: TestPeerIdentity,
    private val peers: Map<PeerId, TestPeerIdentity>,
    private val peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    private val torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    val torUpdates: MutableList<Pair<PeerId, TorEndpoint>> = mutableListOf(),
    private val crypto: CryptoProvider = DefaultCryptoProvider(),
    private val accountStatuses: Map<AccountId, IdentityStatus> = emptyMap(),
    private val deviceStatuses: Map<PeerId, IdentityStatus> = emptyMap(),
    private val localAccount: AccountId = AccountId("local-account"),
) : LocalIdentity, LocalKeys, PeerKeys, DeviceDirectory, DeviceRouteStore {

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity = local.device

    override suspend fun getLocalAccountIdentity(): AccountIdentity =
        error("E2eeIdentityResolverForRouter: account record not stubbed")

    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? =
        deviceStatuses[deviceId]
            ?: if (deviceId == local.device.deviceId || deviceId in peers) IdentityStatus.ACTIVE else null

    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? =
        accountStatuses[accountId]

    suspend fun isLocalAccountAdmin(): Boolean =
        error("E2eeIdentityResolverForRouter: admin flag not stubbed")

    suspend fun isLocalAccountOwner(): Boolean =
        error("E2eeIdentityResolverForRouter: owner flag not stubbed")

    override suspend fun localDeviceSigningKey(): SigningPrivateKey = local.signingPrivateKey

    override suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey = local.encryptionPrivateKey

    override suspend fun localAccountSigningKey(): SigningPrivateKey =
        error("E2eeIdentityResolverForRouter: account private key not stubbed")

    override suspend fun getLocalDeviceId(): PeerId = local.device.deviceId
    override suspend fun getLocalAccountId(): AccountId = localAccount

    override suspend fun resolvePeerIdentity(deviceId: PeerId): DeviceIdentity =
        peers[deviceId]?.device ?: throw CryptoException.MissingDeviceRecord(deviceId.id)

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? =
        torByPeer[deviceId]

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersByAccount[accountId].orEmpty()

    suspend fun getAllPeers(): List<PeerId> = peers.keys.toList()

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        peersByAccount.entries.firstOrNull { deviceId in it.value }?.key

    override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torUpdates.add(deviceId to torEndpoint)
        torByPeer[deviceId] = torEndpoint
    }

    override suspend fun resolvePeerPreKeyBundle(
        deviceId: PeerId,
        signedPreKeyId: SignedPreKeyId?,
    ): PeerPreKeyBundle {
        val device = resolvePeerIdentity(deviceId)
        val signedPreKey = when {
            signedPreKeyId != null -> {
                device.signedPreKey?.takeIf { it.id == signedPreKeyId }
                    ?: error("Signed prekey not found: $signedPreKeyId")
            }

            else -> device.signedPreKey
                ?: error("Missing signed prekey on roster for deviceId=$deviceId")
        }
        require(crypto.verifyDetached(device.signingKey, signedPreKey.publicKey.bytes, signedPreKey.signature)) {
            "failed to verify signed prekey signature"
        }
        return PeerPreKeyBundle(identityKey = device.encryptionKey, signedPreKey = signedPreKey)
    }

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey = local.signedPreKey

    override suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey {
        require(id == local.signedPreKey.id) { "Signed prekey not found: $id" }
        return local.signedPreKey
    }

    override suspend fun getAllActiveDeviceIds(): List<PeerId> =
        getAllPeers().filter { getDeviceStatus(it) != IdentityStatus.BANNED }

    override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
        accountIds.flatMap { getAllPeerDevicesForAccount(it) }


    override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? = resolveTorEndpointForDevice(deviceId)
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
        localIdentity = identity,
        localKeys = identity,
        peerKeys = identity,
        opkRepository = InMemoryOpkRepository(crypto),
        clock = clock,
        cryptoLimits = MutableStateFlow(testCryptoLimits()),
        sessionConfig = MutableStateFlow(
            CryptoSessionConfig()
        )
    )
    val signatureProvider = DefaultSignatureProvider(identity, identity, crypto)
    val protection = DefaultEnvelopeProtectionService(
        webRtcSignalProtection = SignedWebRtcSignalProtection(signatureProvider, crypto),
        fileProtection = PassthroughFileProtection(),
        messageProtection = SignedAndEncryptedMessageProtection(signatureProvider, sessionManager, crypto),
        systemProtection = SignedSystemProtection(signatureProvider, crypto),
        bootstrapProtection = BootstrapProtection(crypto, bootstrapKeySource),
    )
    return E2eeRouterTestStack(
        peer = local,
        identity = identity,
        protection = protection,
    )
}

internal class FakeFrontierSnapshotProvider : FrontierSnapshotProvider {
    override suspend fun latestRoomFrontiers(account: AccountId): List<Pair<RoomId, List<Uuid>>> = emptyList()
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
        localIdentity = stack.identity,
        localDeviceId = stack.peer.device.deviceId,
        devices = stack.identity,
        routes = stack.identity,
        packetDeduplicator = dedup,
        packetOutbox = outbox,
        envelopeProtectionService = stack.protection,
        clock = clock,
        routerConfig = MutableStateFlow(routerConfig),
        transportLimits = MutableStateFlow(testTransportLimits()),
        syncAttemptStore = InMemorySyncAttemptStore(),
        syncPayloadProvider = syncPayloadProvider,
        frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
        peerAvailabilityStore = FakePeerAvailabilityStore(),
    )

internal fun outboxProcessorUnderTest(
    tor: TorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    outbox: PacketOutbox = TrackingPacketOutbox(),
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    routerConfig: RouterConfig = RouterConfig(),
    isPeerOnline: (PeerId) -> Boolean = { false },
    onNoLink: suspend (PeerId) -> Unit = {},
    relayCopies: suspend (BinaryEnvelope) -> Int = { 0 },
): OutboxProcessor {
    val ctx =
        RoutingContext(
            localIdentity = identity,
            localDeviceId = identity.localDeviceId,
            devices = identity,
            routes = identity,
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
        transportPolicy = LinkPreferencePolicy(MutableStateFlow(routerConfig)),
        packetOutbox = outbox,
        maxIdlePoll = MutableStateFlow(routerConfig.retryLoopMaxIdlePoll),
        isPeerOnline = isPeerOnline,
        onNoLink = onNoLink,
        relayCopies = relayCopies,
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
        localIdentity = identity,
        localDeviceId = identity.localDeviceId,
        devices = identity,
        routes = identity,
        packetDeduplicator = dedup,
        packetOutbox = outbox,
        envelopeProtectionService = envelopeProtectionService,
        clock = clock,
        routerConfig = MutableStateFlow(routerConfig),
        transportLimits = MutableStateFlow(testTransportLimits()),
        syncAttemptStore = InMemorySyncAttemptStore(),
        syncPayloadProvider = syncPayloadProvider,
        frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
        peerAvailabilityStore = FakePeerAvailabilityStore(),
    )
