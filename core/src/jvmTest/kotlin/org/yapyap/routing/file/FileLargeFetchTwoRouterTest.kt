package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.e2ee.CryptoSessionConfig
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.e2ee.manager.DefaultCryptoSessionManager
import org.yapyap.crypto.e2ee.manager.SessionUpgradePolicy
import org.yapyap.crypto.e2ee.session.X3dhHandshake
import org.yapyap.crypto.e2ee.testCryptoLimits
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.logging.AppLog
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.VerificationState
import org.yapyap.orchestrator.file.DefaultFileDescriptorStore
import org.yapyap.orchestrator.file.DefaultFilePlaintextSource
import org.yapyap.orchestrator.file.DefaultFileReceiveStore
import org.yapyap.orchestrator.file.DefaultFileRequestCoordinator
import org.yapyap.orchestrator.file.DefaultFileRequestSource
import org.yapyap.orchestrator.file.DefaultFileStore
import org.yapyap.orchestrator.file.DefaultPendingFileRequestStore
import org.yapyap.orchestrator.file.FileDescriptorStore
import org.yapyap.orchestrator.file.FileImportSource
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.file.receive.ReceiveEngine
import org.yapyap.orchestrator.file.receive.ReceivePaths
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.runtime.file.DefaultFileTransferService
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.orchestrator.store.DefaultRoomRepository
import org.yapyap.orchestrator.sync.DefaultRoomVisibility
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.SignedAndEncryptedFileProtection
import org.yapyap.protection.envelope.SignedAndEncryptedMessageProtection
import org.yapyap.protection.envelope.SignedAndEncryptedWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedSystemProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomMemberRole
import org.yapyap.protocol.RoomMemberStatus
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.FileFetchFrame
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.router.FakeFrontierSnapshotProvider
import org.yapyap.routing.router.FakeSyncPayloadProvider
import org.yapyap.routing.router.InMemoryFileIncomingTransferStore
import org.yapyap.routing.router.InMemoryFilePushStore
import org.yapyap.routing.router.InMemoryFileRelayStore
import org.yapyap.routing.router.InMemoryFileRequestAttemptStore
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InMemorySyncAttemptStore
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.store.OutboxTag
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.PrintingAppLogger
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.testfixtures.importHeld
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcDataType
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Step 22 end-to-end: two routers move a large file over WebRTC fetch channels,
 * driven by the real receive engine, serve gate and request coordinator. The Tor
 * mesh only carries signaling-sized traffic; no FILE packet and no FILE outbox
 * row is ever produced on either side.
 */
class FileLargeFetchTwoRouterTest {

    private val aliceAccount = AccountId("file-large-alice-account")
    private val bobAccount = AccountId("file-large-bob-account")
    private val roomId = RoomId(Uuid.random())
    private val fileBytesTotal = 12L * 1024L * 1024L
    private val fileName = "big.bin"
    private val mimeType = "application/octet-stream"
    private val now = epochSeconds(10_000L)

    private class FakePipeline : InboundMessagePipeline {
        private val flow = MutableSharedFlow<org.yapyap.orchestrator.dag.IngestResult>(extraBufferCapacity = 64)
        override val ingestResults = flow
        suspend fun emit(result: org.yapyap.orchestrator.dag.IngestResult) {
            flow.emit(result)
        }

        override fun start(scope: CoroutineScope) = Unit
    }

    private data class Node(
        val router: DefaultRouter,
        val coordinator: DefaultFileRequestCoordinator,
        val engine: ReceiveEngine,
        val facade: DefaultFileTransferService,
        val db: YapYapDatabase,
        val receives: DefaultFileReceiveStore,
        val descriptors: FileDescriptorStore,
        val messages: DefaultMessageRepository,
        val rooms: DefaultRoomRepository,
        val pending: DefaultPendingFileRequestStore,
        val fileStore: DefaultFileStore,
        val tor: RecordingTorTransport,
        val webRtc: RecordingWebRtcTransport,
        val outbox: TrackingPacketOutbox,
        val pipeline: FakePipeline,
        val clock: FakeClock,
        val work: Path,
    )

    private suspend fun buildNode(
        localPeer: org.yapyap.crypto.e2ee.TestPeerIdentity,
        peers: Map<PeerId, org.yapyap.crypto.e2ee.TestPeerIdentity>,
        peersByAccount: Map<AccountId, List<PeerId>>,
        torByPeer: MutableMap<PeerId, TorEndpoint>,
        localTor: RecordingTorTransport,
        localWebRtc: RecordingWebRtcTransport,
        clock: FakeClock,
        orchestratorConfig: OrchestratorConfig,
        routerConfig: RouterConfig,
        tag: String,
    ): Node {
        val crypto = DefaultCryptoProvider()
        val db = openMemoryDatabase().database
        val receives = DefaultFileReceiveStore(db)
        val descriptors = DefaultFileDescriptorStore(db)
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val pending = DefaultPendingFileRequestStore(db)
        val files = JvmFileAccess()
        val work = Path(SystemTemporaryDirectory.toString(), "yapyap-file-large-$tag-${Uuid.random()}")
        files.createDirectories(work)
        val storeDir = Path(work.toString(), "store")
        val incomingDir = Path(work.toString(), "incoming")
        files.createDirectories(storeDir)
        files.createDirectories(incomingDir)
        val config = MutableStateFlow(orchestratorConfig)
        val fileStore = DefaultFileStore(descriptors, crypto, files, storeDir, config)
        val chunkSize = MutableStateFlow(32 * 1024)

        fun sessionManager(identity: org.yapyap.routing.router.E2eeIdentityResolverForRouter) =
            DefaultCryptoSessionManager(
                crypto = crypto,
                x3dh = X3dhHandshake(crypto),
                sessionStore = MapBackedCryptoSessionStore(),
                localIdentity = identity,
                localKeys = identity,
                peerKeys = identity,
                opkRepository = InMemoryOpkRepository(crypto),
                cryptoLimits = MutableStateFlow(testCryptoLimits()),
                clock = clock,
                upgradePolicy = SessionUpgradePolicy.NEVER,
                sessionConfig = MutableStateFlow(CryptoSessionConfig()),
            )

        val identity = org.yapyap.routing.router.E2eeIdentityResolverForRouter(
            local = localPeer,
            peers = peers,
            peersByAccount = peersByAccount,
            torByPeer = torByPeer,
            crypto = crypto,
        )
        val signer = DefaultSignatureProvider(identity, identity, crypto)
        val protection = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = SignedAndEncryptedWebRtcSignalProtection(signer, sessionManager(identity), crypto),
            fileProtection = SignedAndEncryptedFileProtection(signer, sessionManager(identity), crypto),
            messageProtection = SignedAndEncryptedMessageProtection(signer, sessionManager(identity), crypto),
            systemProtection = SignedSystemProtection(signer, crypto),
            bootstrapProtection = BootstrapProtection(crypto) { null },
        )
        val packetOutbox = TrackingPacketOutbox()
        val engine = ReceiveEngine(
            receives = receives,
            descriptors = descriptors,
            crypto = crypto,
            files = files,
            paths = ReceivePaths(incomingDir, storeDir),
            config = config,
            fileStore = fileStore,
            clock = clock,
        )
        val requestSource = DefaultFileRequestSource(
            messages = messages,
            visibility = DefaultRoomVisibility(messages, rooms),
            receives = receives,
            descriptors = descriptors,
            localIdentity = identity,
            coverage = engine,
        )
        val router = DefaultRouter(
            torTransport = localTor,
            webRtcTransport = localWebRtc,
            localIdentity = identity,
            localDeviceId = localPeer.device.deviceId,
            devices = identity,
            routes = identity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            packetOutbox = packetOutbox,
            envelopeProtectionService = protection,
            clock = clock,
            routerConfig = MutableStateFlow(routerConfig),
            transportLimits = MutableStateFlow(testTransportLimits()),
            syncAttemptStore = InMemorySyncAttemptStore(),
            syncPayloadProvider = FakeSyncPayloadProvider(),
            frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
            peerAvailabilityStore = FakePeerAvailabilityStore(),
            filePorts = FileRoutingPorts(
                ingest = engine,
                requests = requestSource,
                serveGate = requestSource,
                plaintext = DefaultFilePlaintextSource(descriptors, fileStore, files, crypto),
                receiveEnds = engine,
                incoming = InMemoryFileIncomingTransferStore(),
                pushes = InMemoryFilePushStore(),
                relay = InMemoryFileRelayStore(),
                requestAttempts = InMemoryFileRequestAttemptStore(),
                chunkSizeBytes = chunkSize,
                fetchFrameBytes = chunkSize,
            ),
        )
        val pipeline = FakePipeline()
        val coordinator = DefaultFileRequestCoordinator(
            pending = pending,
            receives = receives,
            descriptors = descriptors,
            control = engine,
            messages = messages,
            fileCarrier = router.files,
            pipeline = pipeline,
            receiveEvents = engine.receiveEvents,
            config = config,
            clock = clock,
        )
        val facade = DefaultFileTransferService(
            receives = receives,
            descriptors = descriptors,
            files = files,
            requests = coordinator,
            store = fileStore,
        )
        return Node(
            router, coordinator, engine, facade, db, receives, descriptors, messages, rooms, pending,
            fileStore, localTor, localWebRtc, packetOutbox, pipeline, clock, work,
        )
    }

    private data class Mesh(
        val alice: Node,
        val bob: Node,
        val alicePeer: org.yapyap.crypto.e2ee.TestPeerIdentity,
        val bobPeer: org.yapyap.crypto.e2ee.TestPeerIdentity,
        val scope: CoroutineScope,
        val jobs: MutableList<Job>,
        val fetchFromOffsets: MutableList<Long>,
        var simulateSession: Boolean,
    )

    private fun Mesh.pumpTor(): Job = scope.launch {
        var aDelivered = 0
        var bDelivered = 0
        while (true) {
            delay(20.milliseconds)
            val aSends = alice.tor.sendsExcludingHeartbeat().toList()
            while (aDelivered < aSends.size) {
                val (_, env) = aSends[aDelivered++]
                bob.tor.tryEmitIncoming(TorIncomingEnvelope(alice.tor.advertisedEndpoint, env))
            }
            val bSends = bob.tor.sendsExcludingHeartbeat().toList()
            while (bDelivered < bSends.size) {
                val (_, env) = bSends[bDelivered++]
                alice.tor.tryEmitIncoming(TorIncomingEnvelope(bob.tor.advertisedEndpoint, env))
            }
        }
    }

    private fun Mesh.pumpFiles(): Job = scope.launch {
        val seenAToB = mutableSetOf<Triple<PeerId, String, ByteArray>>()
        val seenBToA = mutableSetOf<Triple<PeerId, String, ByteArray>>()
        val seenAClosed = mutableSetOf<Pair<PeerId, String>>()
        val seenBClosed = mutableSetOf<Pair<PeerId, String>>()
        while (true) {
            delay(10.milliseconds)
            for (sent in alice.webRtc.sendFileFrameCalls.toList()) {
                if (seenAToB.add(sent)) {
                    val (target, label, payload) = sent
                    val frame = runCatching { FileFetchFrame.decode(payload) }.getOrNull()
                    if (frame is FileFetchFrame.Fetch) fetchFromOffsets += frame.fromOffset
                    val incoming = WebRtcDataFrame(
                        alicePeer.device.deviceId, target, WebRtcDataType.FILE_DATA, payload, label,
                    )
                    while (!bob.webRtc.tryEmitFileFrame(incoming)) delay(5)
                }
            }
            for (sent in bob.webRtc.sendFileFrameCalls.toList()) {
                if (seenBToA.add(sent)) {
                    val (target, label, payload) = sent
                    val frame = runCatching { FileFetchFrame.decode(payload) }.getOrNull()
                    if (frame is FileFetchFrame.Fetch) fetchFromOffsets += frame.fromOffset
                    val incoming = WebRtcDataFrame(
                        bobPeer.device.deviceId, target, WebRtcDataType.FILE_DATA, payload, label,
                    )
                    while (!alice.webRtc.tryEmitFileFrame(incoming)) delay(5)
                }
            }
            for (closed in alice.webRtc.closeFileChannelCalls.toList()) {
                if (seenAClosed.add(closed)) {
                    bob.webRtc.tryEmitFileChannelEvent(WebRtcFileChannelEvent.Removed(closed.first, closed.second))
                }
            }
            for (closed in bob.webRtc.closeFileChannelCalls.toList()) {
                if (seenBClosed.add(closed)) {
                    alice.webRtc.tryEmitFileChannelEvent(WebRtcFileChannelEvent.Removed(closed.first, closed.second))
                }
            }
        }
    }

    private fun Mesh.simulateSessions(): Job = scope.launch {
        val simulated = mutableSetOf<PeerId>()
        while (true) {
            delay(10.milliseconds)
            if (!simulateSession) continue
            for (peer in bob.webRtc.openSessionCalls.toList()) {
                if (simulated.add(peer)) {
                    bob.webRtc.setSessionState(WebRtcSessionState(peer, SessionPhase.CONNECTED))
                    bob.webRtc.simulateEnvelopeChannelOpen(peer)
                }
            }
        }
    }

    private fun Mesh.advanceClock(): Job = scope.launch {
        while (true) {
            delay(50.milliseconds)
            alice.clock.advanceBy(100.milliseconds)
            bob.clock.advanceBy(100.milliseconds)
        }
    }

    private suspend fun buildMesh(
        bobAutoDownload: Long = 250L * 1024L * 1024L,
        routerConfig: RouterConfig = RouterConfig(
            fileSessionOpenTimeout = 5.seconds,
            retryLoopMaxIdlePoll = 1.seconds,
        ),
        simulateSession: Boolean = true,
    ): Mesh {
        val crypto = DefaultCryptoProvider()
        val alicePeer = buildTestPeerIdentity(crypto, "file-large-alice")
        val bobPeer = buildTestPeerIdentity(crypto, "file-large-bob")
        val clock = FakeClock(now)
        val fileConfig = OrchestratorConfig(
            fileCheckpointBytes = 16L * 1024L,
            fileCheckpointInterval = 200.milliseconds,
        )
        val bobFileConfig = fileConfig.copy(autoDownloadMaxBytes = bobAutoDownload)
        val aliceTor = RecordingTorTransport(TorEndpoint("alice-large.onion", 80))
        val bobTor = RecordingTorTransport(TorEndpoint("bob-large.onion", 80))
        val aliceWebRtc = RecordingWebRtcTransport()
        val bobWebRtc = RecordingWebRtcTransport()
        val alice = buildNode(
            localPeer = alicePeer,
            peers = mapOf(bobPeer.device.deviceId to bobPeer),
            peersByAccount = mapOf(bobAccount to listOf(bobPeer.device.deviceId)),
            torByPeer = mutableMapOf(bobPeer.device.deviceId to bobTor.advertisedEndpoint),
            localTor = aliceTor, localWebRtc = aliceWebRtc, clock = clock,
            orchestratorConfig = fileConfig, routerConfig = routerConfig, tag = "alice",
        )
        val bob = buildNode(
            localPeer = bobPeer,
            peers = mapOf(alicePeer.device.deviceId to alicePeer),
            peersByAccount = mapOf(aliceAccount to listOf(alicePeer.device.deviceId)),
            torByPeer = mutableMapOf(alicePeer.device.deviceId to aliceTor.advertisedEndpoint),
            localTor = bobTor, localWebRtc = bobWebRtc, clock = clock,
            orchestratorConfig = bobFileConfig, routerConfig = routerConfig, tag = "bob",
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mesh = Mesh(
            alice, bob, alicePeer, bobPeer, scope, mutableListOf(), mutableListOf(),
            simulateSession = simulateSession,
        )
        return mesh
    }

    private suspend fun Mesh.start() {
        alice.router.start()
        bob.router.start()
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        alice.engine.start(engineScope)
        bob.engine.start(engineScope)
        jobs += engineScope.coroutineContext[Job]!!
        alice.coordinator.start(scope)
        bob.coordinator.start(scope)
        jobs += pumpTor()
        jobs += pumpFiles()
        jobs += simulateSessions()
        jobs += advanceClock()
    }

    private fun Mesh.stop() {
        jobs.forEach { runCatching { it.cancel() } }
    }

    private suspend fun Mesh.publishLargeFile(): MessagePayload.Text {
        val sourceBytes = ByteArray(fileBytesTotal.toInt()) { (it * 7).toByte() }
        val sourcePath = Path(alice.work.toString(), "big.bin")
        JvmFileAccess().openAppendWriter(sourcePath, null).use {
            it.write(sourceBytes)
            it.flushAndSync()
        }
        val descriptor = alice.fileStore.importHeld(
            source = FileImportSource.PathSource(sourcePath),
            fileName = fileName,
            mimeType = mimeType,
            keepCopy = true,
        )
        val mid = Uuid.random()
        val payload = MessagePayload.Text(
            messageId = mid,
            roomId = roomId,
            senderAccountId = aliceAccount,
            authorDeviceId = alicePeer.device.deviceId,
            prevIds = emptyList(),
            createdAt = alice.clock.now(),
            text = "big",
            attachments = listOf(
                MessagePayload.Attachment(descriptor.fileId, fileName, mimeType, descriptor.totalBytes)
            ),
        )
        for (node in listOf(alice, bob)) {
            for (account in listOf(aliceAccount, bobAccount)) {
                node.db.identityQueries.putAccount(
                    account, null, false, AccountRole.MEMBER, IdentityStatus.ACTIVE, "test", false,
                )
            }
            node.rooms.ensureRoomExists(roomId, RoomType.TEXT_CHANNEL, "r")
            node.messages.insert(
                payload, isOrphaned = false, ancestryComplete = true,
                verificationState = VerificationState.VERIFIED,
            )
        }
        // Holder serves the requester's account; requester resolves the holder.
        alice.rooms.upsertMember(roomId, bobAccount, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)
        alice.rooms.upsertMember(roomId, aliceAccount, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)
        return payload
    }

    private fun Mesh.torFilePackets(): List<PacketType> =
        (alice.tor.sendsExcludingHeartbeat().map { it.second } +
                bob.tor.sendsExcludingHeartbeat().map { it.second })
            .map { it.packetType }

    private fun Mesh.outboxFileTags(): List<OutboxTag> {
        val tags = mutableListOf<OutboxTag>()
        for (entry in alice.outbox.enqueued + bob.outbox.enqueued) {
            val tag = (if (entry in alice.outbox.enqueued) alice else bob).outbox.tagOf(entry.packetId)
            if (tag != null) tags += tag
        }
        return tags
    }

    private suspend fun readFinal(node: Node, fileId: org.yapyap.protocol.FileId, size: Int): ByteArray {
        val path = Path(node.work.toString(), "store", fileId.value)
        return JvmFileAccess().openPositionedReader(path).use { reader ->
            val out = ByteArray(size)
            var got = 0
            while (got < out.size) {
                val n = reader.readAt(got.toLong(), out, got, out.size - got)
                check(n > 0)
                got += n
            }
            out
        }
    }

    @BeforeTest
    fun installLogger() = AppLog.init(PrintingAppLogger())

    @AfterTest
    fun resetLogger() = AppLog.reset()

    @Test
    fun autoDownload_endToEnd_largeFileReachesReady() = runBlocking {
        val mesh = buildMesh()
        try {
            mesh.start()
            val payload = mesh.publishLargeFile()
            val fileId = payload.attachments.single().fileId
            mesh.bob.pipeline.emit(org.yapyap.orchestrator.dag.IngestResult.Inserted(payload))
            try {
                withTimeout(90.seconds) {
                    while (mesh.bob.receives.find(fileId)?.status != FileReceiveStatus.READY) delay(100)
                }
            } catch (e: TimeoutCancellationException) {
                val row = mesh.bob.receives.find(fileId)
                java.io.File("/tmp/yapyap-timeout.txt").writeText(
                    "pending=" + mesh.bob.pending.find(fileId) + "\n" +
                            "bobSends=" + mesh.bob.webRtc.sendFileFrameCalls.size + "\n" +
                            "aliceSends=" + mesh.alice.webRtc.sendFileFrameCalls.size + "\n" +
                            "status=" + row?.status + " P=" + row?.contiguousBytes + " mism=" + row?.hashMismatches + "\n" +
                            "staged=" + mesh.bob.receives.stagedByFile(fileId).size + "\n" +
                            "fetchOffsets=" + mesh.fetchFromOffsets + "\n" +
                            "openSession=" + mesh.bob.webRtc.openSessionCalls + "\n" +
                            "bobTor=" + mesh.bob.tor.sendsExcludingHeartbeat().map { it.second.packetType } + "\n"
                )
                throw e
            }
            val stored = readFinal(mesh.bob, fileId, fileBytesTotal.toInt())
            val original = ByteArray(fileBytesTotal.toInt()) { (it * 7).toByte() }
            assertContentEquals(original, stored)
            // The large path never touches Tor payloads or the outbox.
            assertTrue(mesh.torFilePackets().none { it == PacketType.FILE }, "no FILE packet crossed Tor")
            assertTrue(
                mesh.outboxFileTags()
                    .none { it.kind in OutboxTag.Kind.FILE_KINDS || it.kind == OutboxTag.Kind.FILE_REQUEST },
                "no FILE outbox rows on either side",
            )
        } finally {
            mesh.stop()
            runCatching { mesh.alice.router.stop() }
            runCatching { mesh.bob.router.stop() }
            fun deleteRecursively(path: Path) {
                if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
                    SystemFileSystem.list(path).forEach { deleteRecursively(it) }
                }
                SystemFileSystem.delete(path, mustExist = false)
            }
            runCatching { deleteRecursively(mesh.alice.work) }
            runCatching { deleteRecursively(mesh.bob.work) }
        }
    }

    private suspend fun Mesh.teardown() {
        stop()
        runCatching { alice.router.stop() }
        runCatching { bob.router.stop() }
        fun deleteRecursively(path: Path) {
            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
                SystemFileSystem.list(path).forEach { deleteRecursively(it) }
            }
            SystemFileSystem.delete(path, mustExist = false)
        }
        runCatching { deleteRecursively(alice.work) }
        runCatching { deleteRecursively(bob.work) }
    }

    @Test
    fun aboveLimit_gatedUntilAccepted() = runBlocking {
        val mesh = buildMesh(bobAutoDownload = 11L * 1024L * 1024L)
        try {
            mesh.start()
            val payload = mesh.publishLargeFile()
            val fileId = payload.attachments.single().fileId
            mesh.bob.pipeline.emit(org.yapyap.orchestrator.dag.IngestResult.Inserted(payload))
            delay(3000)
            // Gated: local DECLINED, no request, no fetch frames anywhere.
            assertEquals(FileReceiveStatus.DECLINED, mesh.bob.receives.find(fileId)?.status)
            assertNull(mesh.bob.pending.find(fileId))
            assertTrue(mesh.bob.webRtc.openSessionCalls.isEmpty())
            assertTrue(mesh.fetchFromOffsets.isEmpty())
            assertTrue(mesh.alice.webRtc.sendFileFrameCalls.isEmpty())
            // Accept re-opens the pull and the fetch completes end to end.
            mesh.bob.facade.acceptFile(fileId)
            withTimeout(90.seconds) {
                while (mesh.bob.receives.find(fileId)?.status != FileReceiveStatus.READY) delay(100)
            }
            val stored = readFinal(mesh.bob, fileId, fileBytesTotal.toInt())
            assertContentEquals(ByteArray(fileBytesTotal.toInt()) { (it * 7).toByte() }, stored)
            assertNull(mesh.bob.pending.find(fileId))
        } finally {
            mesh.teardown()
        }
    }

    @Test
    fun sessionDrop_midFile_resumesFromCheckpoint() = runBlocking {
        val mesh = buildMesh()
        try {
            mesh.start()
            val payload = mesh.publishLargeFile()
            val fileId = payload.attachments.single().fileId
            mesh.bob.pipeline.emit(org.yapyap.orchestrator.dag.IngestResult.Inserted(payload))
            // Drop the channel mid-fetch (after progress, long before done).
            withTimeout(30.seconds) {
                while ((mesh.bob.receives.find(fileId)?.contiguousBytes ?: 0L) < 65_536L) delay(50)
            }
            if (mesh.bob.receives.find(fileId)?.status == FileReceiveStatus.READY) {
                // Fetch outran the drop window: still a valid end-to-end pass.
                val stored = readFinal(mesh.bob, fileId, fileBytesTotal.toInt())
                assertContentEquals(ByteArray(fileBytesTotal.toInt()) { (it * 7).toByte() }, stored)
                return@runBlocking
            }
            val label = mesh.bob.webRtc.openFileChannelCalls.single().second
            mesh.bob.webRtc.closeFileChannel(mesh.alicePeer.device.deviceId, label)
            // A fresh fetch names the checkpointed P as its offset.
            withTimeout(90.seconds) {
                while (mesh.fetchFromOffsets.size < 2) delay(100)
            }
            assertTrue(mesh.fetchFromOffsets[1] > 0, "resume starts at P, got ${mesh.fetchFromOffsets}")
            withTimeout(90.seconds) {
                while (mesh.bob.receives.find(fileId)?.status != FileReceiveStatus.READY) delay(100)
            }
            val stored = readFinal(mesh.bob, fileId, fileBytesTotal.toInt())
            assertContentEquals(ByteArray(fileBytesTotal.toInt()) { (it * 7).toByte() }, stored)
        } finally {
            mesh.teardown()
        }
    }

    @Test
    fun allHoldersOffline_pendingThenFetchedWhenOnline() = runBlocking {
        val mesh = buildMesh()
        val aliceEndpoint = mesh.alice.tor.advertisedEndpoint
        mesh.bob.tor.unlinkedEndpoints.add(aliceEndpoint)
        try {
            mesh.start()
            val payload = mesh.publishLargeFile()
            val fileId = payload.attachments.single().fileId
            mesh.bob.pipeline.emit(org.yapyap.orchestrator.dag.IngestResult.Inserted(payload))
            delay(2000)
            // Nobody to ask: PENDING, no session, no fetch.
            assertNotNull(mesh.bob.pending.find(fileId))
            assertTrue(mesh.bob.webRtc.openSessionCalls.isEmpty(), "unreachable candidate is not asked")
            assertTrue(mesh.fetchFromOffsets.isEmpty())
            // The holder comes online: the pending request fires and completes.
            mesh.bob.tor.unlinkedEndpoints.remove(aliceEndpoint)
            mesh.bob.tor.setSessionState(aliceEndpoint, SessionPhase.CONNECTED)
            withTimeout(90.seconds) {
                while (mesh.bob.receives.find(fileId)?.status != FileReceiveStatus.READY) delay(100)
            }
            val stored = readFinal(mesh.bob, fileId, fileBytesTotal.toInt())
            assertContentEquals(ByteArray(fileBytesTotal.toInt()) { (it * 7).toByte() }, stored)
        } finally {
            mesh.teardown()
        }
    }

    @Test
    fun holderOnline_noSession_reportsNoDirectConnection() = runBlocking {
        val mesh = buildMesh(simulateSession = false)
        try {
            mesh.start()
            val payload = mesh.publishLargeFile()
            mesh.bob.pipeline.emit(org.yapyap.orchestrator.dag.IngestResult.Inserted(payload))
            val seen = mutableListOf<FileEvent>()
            val collect = launch { mesh.bob.router.files.fileEvents.collect { seen += it } }
            withTimeout(20.seconds) {
                while (seen.none { it is FileEvent.NoDirectConnection }) delay(100)
            }
            collect.cancel()
            // Online-but-unconnectable is never a decline and never falls back to Tor.
            val fileId = payload.attachments.single().fileId
            assertNotNull(mesh.bob.pending.find(fileId), "request stays pending")
            assertTrue(mesh.bob.webRtc.openSessionCalls.isNotEmpty(), "sessions were attempted")
            assertTrue(mesh.fetchFromOffsets.isEmpty(), "no fetch without a session")
            assertTrue(mesh.torFilePackets().none { it == PacketType.FILE }, "nothing falls back to Tor")
        } finally {
            mesh.teardown()
        }
    }
}
