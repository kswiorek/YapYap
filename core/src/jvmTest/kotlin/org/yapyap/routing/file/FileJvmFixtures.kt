package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.crypto.identity.LocalKeys
import org.yapyap.crypto.identity.LocalSignedPreKey
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protection.envelope.HopProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection
import org.yapyap.protection.service.EnvelopeProtections
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.SyncId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.protocol.envelopes.FileSeal
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.protocol.envelopes.UnsignedEnvelope
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalKind
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.link.TorPeerSessions
import org.yapyap.routing.message.port.FrontierSnapshotProvider
import org.yapyap.routing.message.port.SyncPayloadProvider
import org.yapyap.routing.presence.PeerAvailabilityRegistry
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.router.DeviceDirectory
import org.yapyap.routing.router.DeviceRouteStore
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.DefaultFileIncomingStore
import org.yapyap.routing.store.DefaultFilePullAttemptStore
import org.yapyap.routing.store.DefaultFileSealStore
import org.yapyap.routing.store.DefaultFileTransferStore
import org.yapyap.routing.store.DefaultPacketDeduplicator
import org.yapyap.routing.store.DefaultPacketOutbox
import org.yapyap.routing.store.DefaultSyncAttemptStore
import org.yapyap.routing.store.DueOutboxRow
import org.yapyap.routing.store.OutboxEntry
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.PacketDeduplicator
import org.yapyap.routing.store.PacketOutbox
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.RetryClass
import org.yapyap.testfixtures.FakeClock
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Self-contained doubles for file/routing jvmTests on the sealed-seal engine. They depend
 * only on production APIs and compiling test fixtures (crypto e2ee fixtures, transport
 * recording fakes), never on the shared routing fixtures, so these tests stand alone.
 */

/** Device/account world without keys: enough for unit tests whose protection is faked. */
internal open class JvmRouterIdentity(
    private val localPeer: PeerId,
    private val peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    private val torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    private val statuses: Map<PeerId, IdentityStatus> = emptyMap(),
    private val accountStatuses: Map<AccountId, IdentityStatus> = emptyMap(),
) : LocalIdentity, DeviceDirectory, DeviceRouteStore {
    val localDeviceId: PeerId get() = localPeer

    private fun known(deviceId: PeerId): Boolean =
        deviceId == localPeer || peersByAccount.values.any { deviceId in it }

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity = DeviceIdentity(
        deviceId = localPeer,
        signingKey = SigningPublicKey(byteArrayOf(1)),
        encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
    )

    override suspend fun getLocalAccountIdentity(): AccountIdentity =
        AccountIdentity(AccountId("jvm-local-account"), "jvm-local")

    override suspend fun getLocalDeviceId(): PeerId = localPeer

    override suspend fun getLocalAccountId(): AccountId =
        peersByAccount.entries.firstOrNull { localPeer in it.value }?.key ?: AccountId("jvm-local-account")

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        peersByAccount.entries.firstOrNull { deviceId in it.value }?.key

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersByAccount[accountId].orEmpty()

    override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
        accountIds.flatMap { getAllPeerDevicesForAccount(it) }

    override suspend fun getAllActiveDeviceIds(): List<PeerId> =
        (peersByAccount.values.flatten() + localPeer).distinct()
            .filter { getDeviceStatus(it) != IdentityStatus.BANNED }

    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? =
        statuses[deviceId] ?: if (known(deviceId)) IdentityStatus.ACTIVE else null

    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? =
        accountStatuses[accountId]

    override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? = torByPeer[deviceId]

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? = torByPeer[deviceId]

    override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torByPeer[deviceId] = torEndpoint
    }
}

/** Hop layer that signs with zero bytes and verifies everything (unit tests, no keys). */
internal class ZeroHopProtection(
    private val crypto: CryptoProvider = DefaultCryptoProvider(),
) : HopProtection {
    override suspend fun sign(envelope: UnsignedEnvelope): BinaryEnvelope = BinaryEnvelope(
        packetId = envelope.packetId,
        packetType = envelope.packetType,
        dispositionRequested = envelope.dispositionRequested,
        createdAt = envelope.createdAt,
        expiresAt = envelope.expiresAt,
        source = envelope.source,
        target = envelope.target,
        routingHeader = envelope.routingHeader,
        content = envelope.content,
        signature = ByteArray(64),
    )

    override suspend fun verify(envelope: BinaryEnvelope): Boolean = true

    override suspend fun contentId(content: ByteArray): ContentId = ContentId(crypto.sha256(content))
}

/**
 * File protection with no sessions: the seal rides in the clear, chunks are identity
 * plus the AEAD overhead padding (so exact-length checks still bite), REQUESTs likewise.
 */
internal class IdentityFileProtection : FileProtection {
    private var nextKeyByte: Byte = 7

    override suspend fun seal(seal: FileSeal, target: PeerId): ByteArray = seal.encode()

    override suspend fun openSeal(content: ByteArray, origin: PeerId): FileSeal =
        FileSeal.decode(content)

    override suspend fun encryptChunk(
        sealId: ContentId,
        key: SealKey,
        offset: Long,
        plaintext: ByteArray,
    ): ByteArray = plaintext + ByteArray(FileProtection.CHUNK_OVERHEAD_BYTES)

    override suspend fun decryptChunk(
        sealId: ContentId,
        key: SealKey,
        offset: Long,
        ciphertext: ByteArray,
    ): ByteArray {
        if (ciphertext.size <= FileProtection.CHUNK_OVERHEAD_BYTES) {
            throw ProtectionException.InvalidEnvelope(IllegalArgumentException("chunk too short"))
        }
        return ciphertext.copyOf(ciphertext.size - FileProtection.CHUNK_OVERHEAD_BYTES)
    }

    override suspend fun sealRequest(request: FileRequest, target: PeerId): ByteArray =
        request.encode()

    override suspend fun openRequest(content: ByteArray, source: PeerId): FileRequest =
        FileRequest.decode(content)

    override fun generateSealKey(): SealKey = SealKey(ByteArray(32) { nextKeyByte++ })
}

/** Message protection with no sessions: the payload rides in the clear. */
internal class IdentityMessageProtection : MessageProtection {
    override suspend fun seal(payload: MessagePayload, finalTarget: PeerId): ByteArray =
        payload.encode()

    override suspend fun open(content: ByteArray, origin: PeerId): MessagePayload =
        MessagePayload.decode(content)
}

/** Signal protection with no sessions: the payload rides in the clear. */
internal class IdentitySignalProtection : WebRtcSignalProtection {
    override suspend fun seal(signal: WebRtcSignal): ByteArray = signal.payload

    override suspend fun open(
        content: ByteArray,
        kind: WebRtcSignalKind,
        source: PeerId,
        target: PeerId,
    ): WebRtcSignal = WebRtcSignal(kind, source, target, content)
}

internal fun jvmProtections(
    crypto: CryptoProvider = DefaultCryptoProvider(),
    file: FileProtection = IdentityFileProtection(),
): EnvelopeProtections = EnvelopeProtections(
    hop = ZeroHopProtection(crypto),
    signal = IdentitySignalProtection(),
    message = IdentityMessageProtection(),
    bootstrap = BootstrapProtection(crypto, BootstrapKeySource { null }),
    file = file,
)

/** Ingest that refuses everything (send-side routers in these tests). */
internal class RefusingIngest : FileIngestPort {
    override suspend fun checkOffer(offer: FileManifest): FileOfferVerdict =
        FileOfferVerdict.Refuse(FileRefusalReason.NO_SPACE)

    override suspend fun ingest(
        fileId: FileId,
        byteOffset: Long,
        plaintext: ByteArray,
    ): FileIngestResult = FileIngestResult.Refused

    override suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult =
        FileAppendResult.Ended
}

/** Request source that never wants or serves anything (send-side routers in these tests). */
internal class RefusingRequests : FileRequestSource {
    override suspend fun heldCoverage(fileId: FileId): FileCoverage? = null
    override suspend fun requestCandidates(fileId: FileId): List<AccountId> = emptyList()
    override suspend fun requestTotalBytes(fileId: FileId): Long? = null
    override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean = false
}

/** Scriptable plaintext source for pusher tests. */
internal class ScriptablePlaintext(
    var describeImpl: (FileId) -> FileManifest? = { null },
    var openImpl: (FileId, Long) -> PlaintextStream? = { _, _ -> null },
) : FilePlaintextSource {
    override suspend fun describe(fileId: FileId): FileManifest? = describeImpl(fileId)

    override suspend fun open(fileId: FileId, fromOffset: Long): PlaintextStream? =
        openImpl(fileId, fromOffset)
}

/** Map-backed packet dedup for tests that don't need the SQL implementation. */
internal class MapPacketDeduplicator : PacketDeduplicator {
    private val seen = mutableSetOf<Pair<Uuid, PeerId>>()
    private val nacks = mutableMapOf<Pair<Uuid, PeerId>, PacketNackReason>()
    private val lock = Mutex()

    override suspend fun firstSeen(
        packetId: Uuid,
        sourceDeviceId: PeerId,
        receivedAt: kotlin.time.Instant,
    ): Boolean = lock.withLock { seen.add(packetId to sourceDeviceId) }

    override suspend fun clearPacket(packetId: Uuid, sourceDeviceId: PeerId) {
        lock.withLock { seen.remove(packetId to sourceDeviceId) }
    }

    override suspend fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        lock.withLock { nacks[packetId to sourceDeviceId] = nackReason }
    }

    override suspend fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason? =
        lock.withLock { nacks[packetId to sourceDeviceId] }

    override suspend fun prune(receivedBefore: kotlin.time.Instant) = Unit
}

/** Sync providers with nothing to say (these tests never sync). */
internal class EmptySyncPayload : SyncPayloadProvider {
    override suspend fun syncRequest(syncId: SyncId): SyncPayload.Request? = null
    override suspend fun syncCandidates(syncId: SyncId): List<AccountId> = emptyList()
    override suspend fun syncsWithCandidate(account: AccountId): List<SyncId> = emptyList()
    override suspend fun serveSync(request: SyncPayload.Request, requester: AccountId): List<DagPayload> =
        emptyList()
}

internal class EmptyFrontiers : FrontierSnapshotProvider {
    override suspend fun latestRoomFrontiers(account: AccountId): List<Pair<RoomId, List<MessageId>>> =
        emptyList()
}

internal fun jvmRoutingContext(
    identity: JvmRouterIdentity,
    tor: RecordingTorTransport,
    webRtc: RecordingWebRtcTransport,
    clock: FakeClock,
    routerConfig: RouterConfig = RouterConfig(),
    protections: EnvelopeProtections = jvmProtections(),
    chunkSize: Int = 220 * 1024,
    transportLimits: org.yapyap.routing.router.TransportLimits = org.yapyap.crypto.e2ee.testTransportLimits(),
): RoutingContext {
    val torPeers = TorPeerSessions(identity, tor)
    return RoutingContext(
        localIdentity = identity,
        localDeviceId = identity.localDeviceId,
        devices = identity,
        torTransport = tor,
        webRtcTransport = webRtc,
        torPeers = torPeers,
        transportLimits = MutableStateFlow(transportLimits),
        fileLimits = MutableStateFlow(FileLimits(chunkSize, chunkSize, maxSealBytes = 64 * 1024)),
        envelopeProtections = protections,
        clock = clock,
        routerConfig = MutableStateFlow(routerConfig),
    )
}

/** A DefaultRouter over real SQL stores on [db], for jvmTests that drive the full router. */
internal fun jvmRouterUnderTest(
    ctx: RoutingContext,
    db: YapYapDatabase,
    identity: JvmRouterIdentity,
    ingest: FileIngestPort = RefusingIngest(),
    requests: FileRequestSource = RefusingRequests(),
    plaintext: FilePlaintextSource = ScriptablePlaintext(),
): DefaultRouter = DefaultRouter(
    ctx = ctx,
    routes = identity,
    packetDeduplicator = DefaultPacketDeduplicator(db),
    packetOutbox = DefaultPacketOutbox(db),
    peerAvailabilityStore = org.yapyap.routing.presence.FakePeerAvailabilityStore(),
    syncAttemptStore = DefaultSyncAttemptStore(db),
    syncPayloadProvider = EmptySyncPayload(),
    frontierSnapshotProvider = EmptyFrontiers(),
    fileIngest = ingest,
    fileRequests = requests,
    filePlaintext = plaintext,
    fileSealStore = DefaultFileSealStore(db),
    fileTransferStore = DefaultFileTransferStore(db),
    fileIncomingStore = DefaultFileIncomingStore(db),
    filePullAttemptStore = DefaultFilePullAttemptStore(db),
)

internal fun jvmAvailabilityRegistry(
    clock: FakeClock,
    routerConfig: RouterConfig,
): PeerAvailabilityRegistry = PeerAvailabilityRegistry(
    clock = clock,
    routerConfig = MutableStateFlow(routerConfig),
    store = org.yapyap.routing.presence.FakePeerAvailabilityStore(),
)

internal class TestPeerKeys(
    private val localSigning: SigningPrivateKey,
    private val localEncryption: EncryptionPrivateKey,
    private val localSpk: LocalSignedPreKey,
) : LocalKeys {
    override suspend fun localDeviceSigningKey(): SigningPrivateKey = localSigning
    override suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey = localEncryption
    override suspend fun localAccountSigningKey(): SigningPrivateKey =
        error("no account key in tests")

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey = localSpk
    override suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey = localSpk
}

/** In-memory packet outbox mirroring the SQL semantics the transfer engine relies on. */
internal class InMemoryPacketOutbox : PacketOutbox {
    private val rows = linkedMapOf<Uuid, OutboxEntry>()
    private val lock = Mutex()

    override suspend fun enqueue(
        envelope: BinaryEnvelope,
        nextRetryAt: Instant,
        relayMessage: Boolean,
        targetEndpoint: TorEndpoint?,
        relayBackup: RelayBackup,
        tag: OutboxTag?,
        retryClass: RetryClass,
    ) {
        lock.withLock {
            rows[envelope.packetId] = OutboxEntry(
                packetId = envelope.packetId,
                envelope = envelope,
                nextRetryAt = nextRetryAt,
                attempts = rows[envelope.packetId]?.attempts ?: 0L,
                targetEndpoint = targetEndpoint,
                tag = tag,
                retryClass = retryClass,
                relayBackup = relayBackup,
            )
        }
    }

    override suspend fun claimRelayBackup(packetId: Uuid): Boolean = lock.withLock {
        val row = rows[packetId] ?: return@withLock false
        if (row.relayBackup != RelayBackup.PENDING) return@withLock false
        rows[packetId] = row.copy(relayBackup = RelayBackup.DEPOSITED)
        true
    }

    override suspend fun remove(packetId: Uuid): OutboxEntry? = lock.withLock { rows.remove(packetId) }

    override suspend fun setDueForTarget(target: PeerId, nextRetryAt: Instant) {
        lock.withLock {
            for ((id, row) in rows) {
                if (row.envelope.target == target) rows[id] = row.copy(nextRetryAt = nextRetryAt)
            }
        }
    }

    override suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Instant, at: Instant) {
        lock.withLock {
            val row = rows[packetId] ?: return@withLock
            rows[packetId] = row.copy(nextRetryAt = nextRetryAt, attempts = row.attempts + 1)
        }
    }

    override suspend fun deferRetry(packetId: Uuid, nextRetryAt: Instant) {
        lock.withLock {
            val row = rows[packetId] ?: return@withLock
            rows[packetId] = row.copy(nextRetryAt = nextRetryAt)
        }
    }

    override suspend fun listAllForTarget(target: PeerId): List<OutboxEntry> = lock.withLock {
        rows.values.filter { it.envelope.target == target }
    }

    override suspend fun listDueIds(
        now: Instant,
        nonFileLimit: Int,
        fileLimit: Int,
        excludeTargets: Collection<PeerId>,
    ): List<DueOutboxRow> = lock.withLock {
        val due = rows.values.filter {
            it.envelope.target !in excludeTargets &&
                    (it.nextRetryAt == null || it.nextRetryAt <= now)
        }
        val nonFile = due.filter { it.tag?.kind != OutboxTag.Kind.FILE_TRANSFER }.take(nonFileLimit)
        val file = due.filter { it.tag?.kind == OutboxTag.Kind.FILE_TRANSFER }.take(fileLimit)
        (nonFile + file).map {
            DueOutboxRow(it.packetId, it.envelope.target, it.attempts, it.tag, it.retryClass)
        }
    }

    override suspend fun getByPacketId(packetId: Uuid): OutboxEntry? = lock.withLock { rows[packetId] }

    override suspend fun deleteByTag(tag: OutboxTag) {
        lock.withLock { rows.keys.removeAll { rows[it]?.tag == tag } }
    }

    override suspend fun pruneExpired(now: Instant): List<OutboxEntry> = lock.withLock {
        val expired = rows.values.filter { it.envelope.expiresAt <= now }
        expired.forEach { rows.remove(it.packetId) }
        expired
    }

    override suspend fun packetIdsByTag(tag: OutboxTag): List<Uuid> = lock.withLock {
        rows.values.filter { it.tag == tag }.map { it.packetId }
    }

    override suspend fun hasTagged(tag: OutboxTag): Boolean = lock.withLock {
        rows.values.any { it.tag == tag }
    }

    override suspend fun isQueued(packetId: Uuid): Boolean = lock.withLock { packetId in rows }

    override suspend fun earliestPendingRetryAt(): Instant? = lock.withLock {
        rows.values.mapNotNull { it.nextRetryAt }.minOrNull()
    }

    override suspend fun earliestPendingRetryAfter(after: Instant): Instant? = lock.withLock {
        rows.values.mapNotNull { it.nextRetryAt }.filter { it > after }.minOrNull()
    }

    override suspend fun relayCacheBytes(): Long = 0L

    override suspend fun pruneRelayOverCapacity(maxBytes: Long): Int = 0

    /** Currently queued envelopes with their tags, in enqueue order (test inspection). */
    suspend fun sent(): List<Pair<BinaryEnvelope, OutboxTag?>> = lock.withLock {
        rows.values.map { it.envelope to it.tag }
    }

    suspend fun contains(packetId: Uuid): Boolean = lock.withLock { packetId in rows }

    suspend fun tagOf(packetId: Uuid): OutboxTag? = lock.withLock { rows[packetId]?.tag }

    fun size(): Int = rows.size
}
