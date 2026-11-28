package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.file.inbound.FileInboundHandler
import org.yapyap.routing.file.inbound.FileServeHandler
import org.yapyap.routing.file.inbound.TargetSide
import org.yapyap.routing.file.large.LargeFileService
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.file.relay.RelayDoor
import org.yapyap.routing.file.relay.RelayForwards
import org.yapyap.routing.file.seal.SealRegistry
import org.yapyap.routing.file.transfer.TransferEngine
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.router.FakeFilePorts
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryFileIncomingStore
import org.yapyap.routing.router.InMemoryFilePullAttemptStore
import org.yapyap.routing.router.InMemoryFileSealStore
import org.yapyap.routing.router.InMemoryFileTransferStore
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.routing.router.passthroughProtections
import org.yapyap.routing.router.testRoutingContext
import org.yapyap.routing.store.FileIncomingStore
import org.yapyap.routing.store.FilePullAttemptStore
import org.yapyap.routing.store.FileSealStore
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.time.Duration.Companion.seconds

/**
 * Scriptable receive/serve ports: one object for both the ingest side and the REQUEST side, so a
 * test can steer verdicts, results and coverage without wiring two fakes.
 */
internal class ScriptedFilePorts(
    var offerVerdict: FileOfferVerdict = FileOfferVerdict.Accept,
    var ingestResult: FileIngestResult = FileIngestResult.Staged(0, 0),
    var coverage: FileCoverage? = null,
    var totalBytes: Long? = null,
    var serveAllowed: Boolean = true,
) : FakeFilePorts {
    val offers = mutableListOf<FileManifest>()
    val ingests = mutableListOf<Triple<FileId, Long, ByteArray>>()
    val servedFor = mutableListOf<Pair<FileId, AccountId>>()

    override suspend fun checkOffer(offer: FileManifest): FileOfferVerdict {
        offers += offer
        return offerVerdict
    }

    override suspend fun ingest(
        fileId: FileId,
        byteOffset: Long,
        plaintext: ByteArray,
    ): FileIngestResult {
        ingests += Triple(fileId, byteOffset, plaintext)
        return ingestResult
    }

    override suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult =
        FileAppendResult.Appended(offset + bytes.size)

    override suspend fun heldCoverage(fileId: FileId): FileCoverage? = coverage

    override suspend fun requestCandidates(fileId: FileId): List<AccountId> = emptyList()

    override suspend fun requestTotalBytes(fileId: FileId): Long? = totalBytes

    override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean {
        servedFor += fileId to requester
        return serveAllowed
    }
}

/** Plaintext source scripted by the test: what a describe/open of a file yields. */
internal class ScriptedPlaintext(
    var describeImpl: (FileId) -> FileManifest? = { null },
    var openImpl: (FileId, Long) -> PlaintextStream? = { _, _ -> null },
) : FilePlaintextSource {
    override suspend fun describe(fileId: FileId): FileManifest? = describeImpl(fileId)

    override suspend fun open(fileId: FileId, fromOffset: Long): PlaintextStream? =
        openImpl(fileId, fromOffset)
}

/**
 * A complete FILE side built from production components over in-memory stores and the
 * passthrough protections: the inbound handler plus everything it dispatches to.
 */
internal class FileInboundHarness(
    val ctx: RoutingContext,
    val handler: FileInboundHandler,
    val seals: SealRegistry,
    val engine: TransferEngine,
    val target: TargetSide,
    val door: RelayDoor,
    val sealStore: FileSealStore,
    val transferStore: FileTransferStore,
    val incomingStore: FileIncomingStore,
    val pullAttempts: FilePullAttemptStore,
    val outbox: TrackingPacketOutbox,
    val outboxProcessor: OutboxProcessor,
    val ports: ScriptedFilePorts,
    val plaintext: ScriptedPlaintext,
    val events: MutableSharedFlow<FileEvent>,
    val localId: PeerId,
    val remoteId: PeerId,
    val remoteAccount: AccountId,
    val clock: FakeClock,
) {
    val fileProtection get() = ctx.envelopeProtections.file
    val hopProtection get() = ctx.envelopeProtections.hop
}

internal fun fileInboundHarness(
    localId: PeerId = PeerId("fi-local-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
    remoteId: PeerId = PeerId("fi-remote-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
    remoteAccount: AccountId = AccountId("fi-remote-account"),
    ports: ScriptedFilePorts = ScriptedFilePorts(),
    plaintext: ScriptedPlaintext = ScriptedPlaintext(),
    now: Long = 10_000L,
    chunkSize: Int = 220 * 1024,
): FileInboundHarness {
    val clock = FakeClock(epochSeconds(now))
    val routerConfig = RouterConfig()
    val configFlow = MutableStateFlow(routerConfig)
    val identity = FakeIdentityResolverForRouter(
        localDevice = DeviceIdentity(
            deviceId = localId,
            signingKey = SigningPublicKey(byteArrayOf(1)),
            encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
        ),
        peersByAccount = mapOf(remoteAccount to listOf(remoteId)),
        torByPeer = mutableMapOf(remoteId to TorEndpoint("${remoteId.id}.onion", 80)),
    )
    val tor = RecordingTorTransport()
    val webRtc = RecordingWebRtcTransport()
    val ctx = testRoutingContext(
        identity = identity,
        tor = tor,
        webRtc = webRtc,
        clock = clock,
        routerConfig = routerConfig,
        protections = passthroughProtections(),
        fileChunkSize = chunkSize,
    )
    ctx.torPeers.bind(remoteId, TorEndpoint("${remoteId.id}.onion", 80))
    val outbox = TrackingPacketOutbox()
    val outboxProcessor = outboxProcessorUnderTest(
        tor = tor,
        webRtc = webRtc,
        identity = identity,
        outbox = outbox,
        clock = clock,
        routerConfig = routerConfig,
    )
    val sealStore: FileSealStore = InMemoryFileSealStore()
    val transferStore: FileTransferStore = InMemoryFileTransferStore()
    val incomingStore: FileIncomingStore = InMemoryFileIncomingStore()
    val pullAttempts = InMemoryFilePullAttemptStore()
    val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
    val packets = FilePackets(ctx)
    val engine =
        TransferEngine(ctx, packets, transferStore, sealStore, incomingStore, plaintext, outboxProcessor, outbox)
    val seals = SealRegistry(ctx, sealStore, transferStore, engine, plaintext, NoRelaySelection, events)
    val forwards = RelayForwards(ctx, incomingStore, transferStore, engine)
    val door = RelayDoor(ctx, packets, incomingStore, forwards)
    val fetchService = LargeFileService(
        devices = ctx.devices,
        transport = ctx.webRtcTransport,
        plaintext = plaintext,
        ingest = ports,
        requests = ports,
        routerConfig = configFlow,
        fileLimits = ctx.fileLimits,
        events = events,
        clock = clock,
    )
    val requestProcessor = FilePullProcessor(
        ctx = ctx,
        packets = packets,
        pullAttempts = pullAttempts,
        requests = ports,
        outboxProcessor = outboxProcessor,
        peerPolicy = DefaultSyncPeerPolicy(ctx),
        maxIdlePoll = MutableStateFlow(1.seconds),
        events = events,
        linkKeeper = idleLinkKeeper(ctx, clock, routerConfig, webRtc = false),
        webRtcLinks = idleLinkKeeper(ctx, clock, routerConfig, webRtc = true),
        largeFiles = fetchService,
    )
    val target = TargetSide(ctx, packets, incomingStore, ports, ports, requestProcessor, outboxProcessor)
    val handler = FileInboundHandler(
        ctx = ctx,
        incomingStore = incomingStore,
        target = target,
        relay = door,
        engine = engine,
        seals = seals,
        serve = FileServeHandler(ctx, ports, seals),
    )
    return FileInboundHarness(
        ctx = ctx,
        handler = handler,
        seals = seals,
        engine = engine,
        target = target,
        door = door,
        sealStore = sealStore,
        transferStore = transferStore,
        incomingStore = incomingStore,
        pullAttempts = pullAttempts,
        outbox = outbox,
        outboxProcessor = outboxProcessor,
        ports = ports,
        plaintext = plaintext,
        events = events,
        localId = localId,
        remoteId = remoteId,
        remoteAccount = remoteAccount,
        clock = clock,
    )
}

private fun idleLinkKeeper(
    ctx: RoutingContext,
    clock: FakeClock,
    config: RouterConfig,
    webRtc: Boolean,
): LinkKeeper = LinkKeeper(
    name = if (webRtc) "test-webrtc" else "test-tor",
    sessions = if (webRtc) ctx.webRtcTransport else ctx.torPeers,
    targets = { emptyList() },
    ready = flowOf(false),
    config = MutableStateFlow(if (webRtc) config.webrtcLinkKeeperConfig else config.torLinkKeeperConfig),
    clock = clock,
)
