package org.yapyap.routing.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.file.seal.SealRegistry
import org.yapyap.routing.file.transfer.TransferEngine
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.outbound.EnvelopeDispatcher
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.presence.FakePeerAvailabilityStore
import org.yapyap.routing.presence.PeerAvailabilityRegistry
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.DefaultFileRelayStore
import org.yapyap.routing.store.DefaultFileSealStore
import org.yapyap.routing.store.DefaultFileTransferStore
import org.yapyap.routing.store.FileSealStore
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.OutgoingTransfer
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal val fixtureFileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
internal val fixtureAccount = AccountId("file-push-account")
internal val localPeerId = PeerId("pusherlocaldeviceaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
internal val peerA = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
internal val peerB = PeerId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
internal val relay1 = PeerId("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
internal val relay2 = PeerId("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")

internal class SealTransferHarness(
    val engine: TransferEngine,
    val seals: SealRegistry,
    val plaintext: ScriptablePlaintext,
    val sealStore: FileSealStore,
    val transferStore: FileTransferStore,
    val outbox: InMemoryPacketOutbox,
    val processor: OutboxProcessor,
    val fileEvents: MutableSharedFlow<FileEvent>,
    val clock: FakeClock,
    val bytes: ByteArray,
    val totalBytes: Long,
    val chunkSize: Int,
    val ctx: RoutingContext,
)

/** Currently queued outbox rows as (envelope, tag) pairs. */
internal suspend fun SealTransferHarness.sent(): List<Pair<BinaryEnvelope, OutboxTag?>> = outbox.sent()

internal fun sealTransferHarness(
    totalBytes: Long = 1024L * 1024,
    chunkSize: Int = 220 * 1024,
    now: Long = 10_000L,
    relaySelection: RelaySelectionPolicy = NoRelaySelection,
    routerConfig: RouterConfig = RouterConfig(),
    fileProtection: FileProtection = IdentityFileProtection(),
): SealTransferHarness {
    val bytes = ByteArray(totalBytes.toInt()) { (it * 7).toByte() }
    val identity = JvmRouterIdentity(localPeerId, mapOf(fixtureAccount to listOf(peerA, peerB)))
    val protection = fileProtection
    val plaintext = ScriptablePlaintext()
    plaintext.describeImpl = { _ ->
        FileManifest(
            fileId = fixtureFileId,
            totalBytes = totalBytes,
            blockSize = 1024 * 1024,
            blockHashes = listOf(ByteArray(32) { 7 }),
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
        )
    }
    plaintext.openImpl = { _, fromOffset ->
        if (fromOffset > totalBytes) null
        else object : PlaintextStream {
            var pos = fromOffset
            override suspend fun read(maxBytes: Int): PlaintextRead {
                if (pos >= totalBytes) return PlaintextRead.Eof
                val end = minOf(pos + maxBytes, totalBytes)
                val slice = bytes.copyOfRange(pos.toInt(), end.toInt())
                pos = end
                return PlaintextRead.Bytes(slice)
            }

            override fun close() = Unit
        }
    }
    // Real SQL stores on in-memory SQLite: restart recovery, the deposit claim and the
    // chunk index all rely on store atomicity, not on fake semantics.
    val db = openMemoryDatabase().database
    val sealStore = DefaultFileSealStore(db, Dispatchers.Unconfined)
    val transferStore = DefaultFileTransferStore(db, Dispatchers.Unconfined)
    val fileEvents = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
    val clock = FakeClock(epochSeconds(now))
    val tor = RecordingTorTransport()
    val webRtc = RecordingWebRtcTransport()
    val ctx = jvmRoutingContext(
        identity = identity,
        tor = tor,
        webRtc = webRtc,
        clock = clock,
        routerConfig = routerConfig,
        protections = jvmProtections(file = protection),
        chunkSize = chunkSize,
    )
    val outbox = InMemoryPacketOutbox()
    val registry = PeerAvailabilityRegistry(
        clock = clock,
        routerConfig = MutableStateFlow(routerConfig),
        store = FakePeerAvailabilityStore(),
    )
    val linkKeeper = LinkKeeper(
        name = "Tor-test",
        sessions = ctx.peerLinks.tor,
        targets = { emptyList() },
        ready = flowOf(false),
        config = MutableStateFlow(routerConfig.torLinkKeeperConfig),
        clock = clock,
    )
    val processor = OutboxProcessor(
        ctx = ctx,
        dispatcher = EnvelopeDispatcher(ctx, identity),
        transportPolicy = LinkPreferencePolicy(MutableStateFlow(routerConfig)),
        packetOutbox = outbox,
        maxIdlePoll = MutableStateFlow(routerConfig.retryLoopMaxIdlePoll),
        peerAvailabilityRegistry = registry,
        linkKeeper = linkKeeper,
        relaySelection = relaySelection,
    )
    val engine = TransferEngine(
        ctx = ctx,
        packets = FilePackets(ctx),
        transferStore = transferStore,
        sealStore = sealStore,
        relayStore = DefaultFileRelayStore(db, Dispatchers.Unconfined),
        plaintext = plaintext,
        outbox = processor,
        outboxStore = outbox,
    )
    val seals = SealRegistry(
        ctx = ctx,
        sealStore = sealStore,
        transferStore = transferStore,
        engine = engine,
        plaintext = plaintext,
        relaySelection = relaySelection,
        events = fileEvents,
    )
    return SealTransferHarness(
        engine = engine,
        seals = seals,
        plaintext = plaintext,
        sealStore = sealStore,
        transferStore = transferStore,
        outbox = outbox,
        processor = processor,
        fileEvents = fileEvents,
        clock = clock,
        bytes = bytes,
        totalBytes = totalBytes,
        chunkSize = chunkSize,
        ctx = ctx,
    )
}

/** Chunk indices of currently queued FILE_TRANSFER rows, in queue order. */
internal suspend fun SealTransferHarness.chunkIndices(): List<Int> = sent().mapNotNull { (env, _) ->
    (FileHeader.decode(env.routingHeader) as? FileHeader.Chunk)?.chunkIndex
}

/** Offer envelopes currently queued. */
internal suspend fun SealTransferHarness.offerHeaders(): List<FileHeader.Offer> = sent().mapNotNull { (env, _) ->
    FileHeader.decode(env.routingHeader) as? FileHeader.Offer
}

/** Live outgoing transfers to [device]. */
internal suspend fun SealTransferHarness.transfersFor(device: PeerId): List<OutgoingTransfer> =
    sealStore.byFile(fixtureFileId).flatMap { transferStore.bySeal(it.sealId) }
        .filter { it.peer == device && it.status.isLive }

/** All transfers of the fixture file (live and ended). */
internal suspend fun SealTransferHarness.allTransfers(): List<OutgoingTransfer> =
    sealStore.byFile(fixtureFileId).flatMap { transferStore.bySeal(it.sealId) }

internal suspend fun SealTransferHarness.offerPacketOf(transferId: TransferId): Uuid {
    withTimeout(5_000.milliseconds) {
        while (transferStore.find(transferId)?.offerPacketId == null) {
            kotlinx.coroutines.delay(10.milliseconds)
        }
    }
    return requireNotNull(transferStore.find(transferId)?.offerPacketId)
}

internal suspend fun SealTransferHarness.ackOffer(transferId: TransferId, target: PeerId): OutboxOutcome.Acked =
    OutboxOutcome.Acked(OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transferId.value), target, offerPacketOf(transferId))

internal suspend fun SealTransferHarness.awaitStatus(transferId: TransferId, vararg want: FileTransferStatus) {
    withTimeout(5.seconds) {
        while (transferStore.find(transferId)?.status !in want) kotlinx.coroutines.delay(10.milliseconds)
    }
}

internal suspend fun SealTransferHarness.awaitOffers(count: Int) {
    withTimeout(5_000.milliseconds) {
        while (offerHeaders().size < count) kotlinx.coroutines.delay(10.milliseconds)
    }
}
