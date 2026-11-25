package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import org.yapyap.orchestrator.file.DefaultFileDescriptorStore
import org.yapyap.orchestrator.file.DefaultFilePlaintextSource
import org.yapyap.orchestrator.file.DefaultFileReceiveStore
import org.yapyap.orchestrator.file.DefaultFileStore
import org.yapyap.orchestrator.file.FileImportSource
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.FileRequestControl
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.file.receive.ReceiveEngine
import org.yapyap.orchestrator.file.receive.ReceivePaths
import org.yapyap.orchestrator.runtime.file.DefaultFileTransferService
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.RatchetFileProtection
import org.yapyap.protection.envelope.RatchetMessageProtection
import org.yapyap.protection.envelope.RatchetSealer
import org.yapyap.protection.envelope.RatchetWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedHopProtection
import org.yapyap.protection.service.EnvelopeProtections
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReceiveEnd
import org.yapyap.routing.link.PeerLinks
import org.yapyap.routing.presence.FakePeerAvailabilityStore
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.router.E2eeIdentityResolverForRouter
import org.yapyap.routing.router.FakeFilePlaintextSource
import org.yapyap.routing.router.FakeFrontierSnapshotProvider
import org.yapyap.routing.router.FakeSyncPayloadProvider
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InMemorySyncAttemptStore
import org.yapyap.routing.router.RefusingFilePorts
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.store.DefaultFileIncomingStore
import org.yapyap.routing.store.DefaultFileRelayStore
import org.yapyap.routing.store.DefaultFileRequestAttemptStore
import org.yapyap.routing.store.DefaultFileSealStore
import org.yapyap.routing.store.DefaultFileTransferStore
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileSealStatus
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.PrintingAppLogger
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.testfixtures.importHeld
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * R3.3 relay path end to end (design §19 small-file rows): sender, two relays and a target
 * over recording transports with a pump that routes by envelope target.
 *
 * Target offline: the sender deposits its remainder at both relays (gone-only stall signal),
 * each relay becomes WHOLE. Target online: both relays forward, the target reaches READY once
 * from the mixed streams, and both relay copies are gone (forget after delivery).
 */
class FileRelayThreeRouterTest {

    @BeforeTest
    fun installLogger() = AppLog.init(PrintingAppLogger())

    @AfterTest
    fun resetLogger() = AppLog.reset()

    @Test
    fun offlineTarget_depositWholeForward_readyOnceAndRelaysDrained(): Unit = runBlocking {
        val crypto = DefaultCryptoProvider()
        val alicePeer = buildTestPeerIdentity(crypto, "relay3-alice")
        val relay1Peer = buildTestPeerIdentity(crypto, "relay3-relay1")
        val relay2Peer = buildTestPeerIdentity(crypto, "relay3-relay2")
        val bobPeer = buildTestPeerIdentity(crypto, "relay3-bob")
        val bobAccount = AccountId("relay3-bob-account")
        val relayAccount = AccountId("relay3-relay-account")
        val clock = FakeClock(epochSeconds(10_000L))

        val aliceTor = RecordingTorTransport(TorEndpoint("relay3-alice.onion", 80))
        val relay1Tor = RecordingTorTransport(TorEndpoint("relay3-relay1.onion", 80))
        val relay2Tor = RecordingTorTransport(TorEndpoint("relay3-relay2.onion", 80))
        val bobTor = RecordingTorTransport(TorEndpoint("relay3-bob.onion", 80))
        val aliceWebRtc = RecordingWebRtcTransport()
        val relay1WebRtc = RecordingWebRtcTransport()
        val relay2WebRtc = RecordingWebRtcTransport()
        val bobWebRtc = RecordingWebRtcTransport()

        fun sessionManager(identity: E2eeIdentityResolverForRouter) = DefaultCryptoSessionManager(
            crypto = crypto,
            x3dh = X3dhHandshake(crypto),
            sessionStore = MapBackedCryptoSessionStore(),
            localIdentity = identity,
            localKeys = identity,
            peerKeys = identity,
            opkRepository = InMemoryOpkRepository(crypto),
            clock = clock,
            upgradePolicy = SessionUpgradePolicy.NEVER,
            sessionConfig = MutableStateFlow(
                CryptoSessionConfig()
            ),
            cryptoLimits = MutableStateFlow(testCryptoLimits()),
        )

        fun protection(
            manager: DefaultCryptoSessionManager,
            signer: DefaultSignatureProvider,
        ): EnvelopeProtections {
            val ratchet = RatchetSealer(manager)
            return EnvelopeProtections(
                hop = SignedHopProtection(signer, crypto),
                signal = RatchetWebRtcSignalProtection(ratchet),
                message = RatchetMessageProtection(ratchet),
                bootstrap = BootstrapProtection(crypto) { null },
                file = RatchetFileProtection(ratchet, crypto),
            )
        }

        val aliceIdentity = E2eeIdentityResolverForRouter(
            local = alicePeer,
            peers = mapOf(
                bobPeer.device.deviceId to bobPeer,
                relay1Peer.device.deviceId to relay1Peer,
                relay2Peer.device.deviceId to relay2Peer,
            ),
            peersByAccount = mapOf(
                bobAccount to listOf(bobPeer.device.deviceId),
                relayAccount to listOf(relay1Peer.device.deviceId, relay2Peer.device.deviceId),
            ),
            torByPeer = mutableMapOf(
                bobPeer.device.deviceId to bobTor.advertisedEndpoint,
                relay1Peer.device.deviceId to relay1Tor.advertisedEndpoint,
                relay2Peer.device.deviceId to relay2Tor.advertisedEndpoint,
            ),
            crypto = crypto,
        )
        val relay1Identity = E2eeIdentityResolverForRouter(
            local = relay1Peer,
            peers = mapOf(alicePeer.device.deviceId to alicePeer, bobPeer.device.deviceId to bobPeer),
            peersByAccount = mapOf(AccountId("relay3-other") to listOf(alicePeer.device.deviceId)),
            torByPeer = mutableMapOf(
                alicePeer.device.deviceId to aliceTor.advertisedEndpoint,
                bobPeer.device.deviceId to bobTor.advertisedEndpoint,
            ),
            crypto = crypto,
        )
        val relay2Identity = E2eeIdentityResolverForRouter(
            local = relay2Peer,
            peers = mapOf(alicePeer.device.deviceId to alicePeer, bobPeer.device.deviceId to bobPeer),
            peersByAccount = mapOf(AccountId("relay3-other") to listOf(alicePeer.device.deviceId)),
            torByPeer = mutableMapOf(
                alicePeer.device.deviceId to aliceTor.advertisedEndpoint,
                bobPeer.device.deviceId to bobTor.advertisedEndpoint,
            ),
            crypto = crypto,
        )
        val bobIdentity = E2eeIdentityResolverForRouter(
            local = bobPeer,
            peers = mapOf(
                alicePeer.device.deviceId to alicePeer,
                relay1Peer.device.deviceId to relay1Peer,
                relay2Peer.device.deviceId to relay2Peer,
            ),
            peersByAccount = mapOf(AccountId("relay3-alice-account") to listOf(alicePeer.device.deviceId)),
            torByPeer = mutableMapOf(
                alicePeer.device.deviceId to aliceTor.advertisedEndpoint,
                relay1Peer.device.deviceId to relay1Tor.advertisedEndpoint,
                relay2Peer.device.deviceId to relay2Tor.advertisedEndpoint,
            ),
            crypto = crypto,
        )

        val aliceSigner = DefaultSignatureProvider(aliceIdentity, aliceIdentity, crypto)
        val relay1Signer = DefaultSignatureProvider(relay1Identity, relay1Identity, crypto)
        val relay2Signer = DefaultSignatureProvider(relay2Identity, relay2Identity, crypto)
        val bobSigner = DefaultSignatureProvider(bobIdentity, bobIdentity, crypto)
        val aliceProtection = protection(sessionManager(aliceIdentity), aliceSigner)
        val relay1Protection = protection(sessionManager(relay1Identity), relay1Signer)
        val relay2Protection = protection(sessionManager(relay2Identity), relay2Signer)
        val bobProtection = protection(sessionManager(bobIdentity), bobSigner)

        val files = JvmFileAccess()
        val fileConfig = MutableStateFlow(OrchestratorConfig())
        val chunkSize = 220 * 1024
        val senderRouterConfig = RouterConfig(chunkAckTimeout = 3.seconds, fileChunkRetryDelay = 1.seconds)
        val relayRouterConfig = RouterConfig(fileChunkRetryDelay = 1.seconds)

        val aliceDb = openMemoryDatabase().database
        val aliceDescriptors = DefaultFileDescriptorStore(aliceDb)
        val aliceWork = Path(SystemTemporaryDirectory, "yapyap-relay3-alice-${Uuid.random()}")
        files.createDirectories(aliceWork)
        val aliceStoreDir = Path(aliceWork.toString(), "store")
        files.createDirectories(aliceStoreDir)
        val aliceFileStore = DefaultFileStore(aliceDescriptors, crypto, files, aliceStoreDir, fileConfig)

        val bobDb = openMemoryDatabase().database
        val bobReceives = DefaultFileReceiveStore(bobDb)
        val bobDescriptors = DefaultFileDescriptorStore(bobDb)
        val bobMessages = DefaultMessageRepository(bobDb)
        val bobWork = Path(SystemTemporaryDirectory, "yapyap-relay3-bob-${Uuid.random()}")
        files.createDirectories(bobWork)
        val bobStoreDir = Path(bobWork.toString(), "store")
        val bobIncomingDir = Path(bobWork.toString(), "incoming")
        files.createDirectories(bobStoreDir)
        files.createDirectories(bobIncomingDir)
        val bobFileStore = DefaultFileStore(bobDescriptors, crypto, files, bobStoreDir, fileConfig)

        val bobEngine = ReceiveEngine(
            receives = bobReceives,
            descriptors = bobDescriptors,
            crypto = crypto,
            files = files,
            paths = ReceivePaths(bobIncomingDir, bobStoreDir),
            config = fileConfig,
            fileStore = bobFileStore,
            clock = clock,
        )

        // Pre-seeded relay scores: both relays qualify (0.7 each reaches the 0.9 target
        // together), so one deposit round covers both.
        val aliceAvailability = FakePeerAvailabilityStore().apply {
            reliability[relay1Peer.device.deviceId] = 0.7
            reliability[relay2Peer.device.deviceId] = 0.7
        }
        val relay1Db = openMemoryDatabase().database
        val relay2Db = openMemoryDatabase().database
        val aliceSealStore = DefaultFileSealStore(aliceDb)
        val aliceTransferStore = DefaultFileTransferStore(aliceDb)
        val aliceOutbox = TrackingPacketOutbox()
        val relay1Store = DefaultFileRelayStore(relay1Db)
        val relay1TransferStore = DefaultFileTransferStore(relay1Db)
        val relay2Store = DefaultFileRelayStore(relay2Db)
        val relay2TransferStore = DefaultFileTransferStore(relay2Db)
        val bobIncoming = DefaultFileIncomingStore(bobDb)

        fun buildFileRouter(
            identity: E2eeIdentityResolverForRouter,
            tor: RecordingTorTransport,
            webRtc: RecordingWebRtcTransport,
            protection: EnvelopeProtections,
            ingest: FileIngestPort,
            plaintext: FilePlaintextSource,
            sealStore: org.yapyap.routing.store.FileSealStore,
            transferStore: org.yapyap.routing.store.FileTransferStore,
            relay: FileRelayStore,
            incomingOverride: org.yapyap.routing.store.FileIncomingStore? = null,
            db: YapYapDatabase = aliceDb,
            routerConfig: RouterConfig = RouterConfig(),
            availability: FakePeerAvailabilityStore = FakePeerAvailabilityStore(),
            outbox: TrackingPacketOutbox = TrackingPacketOutbox(),
        ): DefaultRouter {
            val peerLinks = PeerLinks(identity, webRtc, tor)
            val ctx = RoutingContext(
                localIdentity = identity,
                localDeviceId = identity.localDeviceId,
                devices = identity,
                torTransport = tor,
                webRtcTransport = webRtc,
                peerLinks = peerLinks,
                transportLimits = MutableStateFlow(testTransportLimits()),
                fileLimits = MutableStateFlow(FileLimits(chunkSize, chunkSize)),
                envelopeProtections = protection,
                clock = clock,
                routerConfig = MutableStateFlow(routerConfig),
            )
            return DefaultRouter(
                ctx = ctx,
                routes = identity,
                packetDeduplicator = InMemoryPacketDeduplicator(),
                packetOutbox = outbox,
                peerAvailabilityStore = availability,
                syncAttemptStore = InMemorySyncAttemptStore(),
                syncPayloadProvider = FakeSyncPayloadProvider(),
                frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
                fileIngest = ingest,
                fileRequests = RefusingFilePorts(),
                filePlaintext = plaintext,
                fileSealStore = sealStore,
                fileTransferStore = transferStore,
                fileIncomingStore = incomingOverride ?: DefaultFileIncomingStore(db),
                fileRelayStore = relay,
                fileRequestAttemptStore = DefaultFileRequestAttemptStore(db),
            )
        }

        val aliceRouter = buildFileRouter(
            aliceIdentity, aliceTor, aliceWebRtc, aliceProtection,
            RefusingFilePorts(),
            DefaultFilePlaintextSource(aliceDescriptors, aliceFileStore, files, crypto),
            aliceSealStore,
            aliceTransferStore,
            DefaultFileRelayStore(aliceDb),
            db = aliceDb,
            routerConfig = senderRouterConfig,
            availability = aliceAvailability,
            outbox = aliceOutbox,
        )
        val relay1Router = buildFileRouter(
            relay1Identity, relay1Tor, relay1WebRtc, relay1Protection,
            RefusingFilePorts(),
            FakeFilePlaintextSource(),
            DefaultFileSealStore(relay1Db),
            relay1TransferStore,
            relay1Store,
            db = relay1Db,
            routerConfig = relayRouterConfig,
        )
        val relay2Router = buildFileRouter(
            relay2Identity, relay2Tor, relay2WebRtc, relay2Protection,
            RefusingFilePorts(),
            FakeFilePlaintextSource(),
            DefaultFileSealStore(relay2Db),
            relay2TransferStore,
            relay2Store,
            db = relay2Db,
            routerConfig = relayRouterConfig,
        )
        val bobRouter = buildFileRouter(
            bobIdentity, bobTor, bobWebRtc, bobProtection,
            bobEngine,
            DefaultFilePlaintextSource(bobDescriptors, bobFileStore, files, crypto),
            DefaultFileSealStore(bobDb),
            DefaultFileTransferStore(bobDb),
            DefaultFileRelayStore(bobDb),
            incomingOverride = bobIncoming,
            db = bobDb,
        )
        val bobFiles = DefaultFileTransferService(
            receives = bobReceives,
            descriptors = bobDescriptors,
            files = files,
            requests = object : FileRequestControl {
                override suspend fun acceptFile(fileId: FileId) = error("not used")
                override suspend fun declineFile(fileId: FileId) = error("not used")
            },
            store = bobFileStore,
        )

        try {
            aliceRouter.start()
            relay1Router.start()
            relay2Router.start()
            bobRouter.start()
            val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            bobEngine.start(engineScope)

            // The routers read a frozen FakeClock: advance it in real time so outbox
            // retries become due exactly as in production (offline holds need retries).
            val clockJob = launch {
                while (true) {
                    delay(100.milliseconds)
                    clock.advanceBy(100.milliseconds)
                }
            }

            // Pump every direction, routing by envelope target. Bob starts offline: its
            // envelopes are held until the test brings it online after the deposit.
            // forwardedChunks counts relay-streamed CHUNKs per transfer, proving both
            // relays actually served bytes (not just that their rows were dropped).
            // completeCounts counts COMPLETEs per transfer: exactly one per author proves
            // the receiver reported READY once (a second Ready would find no rows to answer).
            var bobOffline = true
            val forwardedChunks = mutableMapOf<TransferId, Int>()
            var completeCount = 0
            val heldForBob = ArrayDeque<Pair<TorEndpoint, BinaryEnvelope>>()
            val transports = listOf(aliceTor, relay1Tor, relay2Tor, bobTor)
            val byDevice: Map<PeerId, RecordingTorTransport> = mapOf(
                alicePeer.device.deviceId to aliceTor,
                relay1Peer.device.deviceId to relay1Tor,
                relay2Peer.device.deviceId to relay2Tor,
                bobPeer.device.deviceId to bobTor,
            )
            val pump = launch {
                val delivered = mutableMapOf<RecordingTorTransport, Int>()
                while (true) {
                    delay(20.milliseconds)
                    if (!bobOffline) {
                        while (heldForBob.isNotEmpty()) {
                            val (from, env) = heldForBob.removeFirst()
                            bobTor.tryEmitIncoming(TorIncomingEnvelope(from, env))
                        }
                    }
                    for (t in transports) {
                        // The transport appends concurrently; a torn read is retried next pass.
                        val sends = runCatching { t.sendsExcludingHeartbeat() }.getOrNull() ?: continue
                        var idx = delivered.getOrDefault(t, 0)
                        while (idx < sends.size) {
                            val (from, env) = sends[idx]
                            idx++
                            val dest = byDevice[env.target]
                            if (dest == null) continue
                            if (bobOffline && dest == bobTor) {
                                heldForBob += from to env
                            } else {
                                if (dest == bobTor) {
                                    runCatching {
                                        val header = FileHeader.decode(env.routingHeader)
                                        if (header is FileHeader.Chunk) {
                                            forwardedChunks[header.transferId] =
                                                (forwardedChunks[header.transferId] ?: 0) + 1
                                        }
                                    }
                                }
                                if (dest == aliceTor) {
                                    runCatching {
                                        val header = FileHeader.decode(env.routingHeader)
                                        if (header is FileHeader.Complete) {
                                            completeCount += 1
                                        }
                                    }
                                }
                                dest.tryEmitIncoming(TorIncomingEnvelope(from, env))
                            }
                        }
                        delivered[t] = idx
                    }
                }
            }

            try {
                val original = ByteArray(10 * 1024 * 1024) { (it * 7).toByte() }
                val sourcePath = Path(aliceWork.toString(), "big.bin")
                files.openAppendWriter(sourcePath, null).use {
                    it.write(original)
                    it.flushAndSync()
                }
                val descriptor = aliceFileStore.importHeld(
                    source = FileImportSource.PathSource(sourcePath),
                    fileName = "big.bin",
                    mimeType = "application/octet-stream",
                    keepCopy = true,
                )
                val fileId = descriptor.fileId

                aliceRouter.files.pushFile(fileId, bobAccount)

                // Target offline: the sender deposits, both relays take the full suffix.
                val expectedChunks = ((descriptor.totalBytes - 1) / chunkSize + 1).toInt()
                withTimeout(120.seconds) {
                    var n = 0
                    while (
                        relay1Store.all().none { row ->
                            row.status == FileRelayStatus.WHOLE &&
                                    relay1Store.chunkIndices(row.transferId).size >= expectedChunks
                        } ||
                        relay2Store.all().none { row ->
                            row.status == FileRelayStatus.WHOLE &&
                                    relay2Store.chunkIndices(row.transferId).size >= expectedChunks
                        }
                    ) {
                        delay(100.milliseconds)
                        if (++n % 50 == 0) {
                            println(
                                "DIAG outbox=" + aliceOutbox.enqueued.size +
                                        " sender=" + aliceSealStore.byFile(fileId).map { it.status } +
                                        " r1=" + relay1Store.all()
                                    .map { it.status to relay1Store.chunkIndices(it.transferId).size } +
                                        " r2=" + relay2Store.all()
                                    .map { it.status to relay2Store.chunkIndices(it.transferId).size },
                            )
                        }
                    }
                }
                val r1InboundId = relay1Store.all().single().transferId
                val r2InboundId = relay2Store.all().single().transferId
                val r1ForwardId = relay1TransferStore.byRelayTransfer(r1InboundId).single().transferId
                val r2ForwardId = relay2TransferStore.byRelayTransfer(r2InboundId).single().transferId

                // Target online: both relays forward; the target reaches READY once.
                bobOffline = false
                val snapshot = withTimeout(300.seconds) {
                    var latest = bobFiles.receiveState(fileId)
                    while (latest?.status != FileReceiveStatus.READY) {
                        delay(100.milliseconds)
                        latest = bobFiles.receiveState(fileId)
                    }
                    latest
                }
                assertNotNull(snapshot, "receive must reach READY")
                assertEquals(fileId, snapshot.fileId)
                assertEquals(descriptor.totalBytes, snapshot.totalBytes)
                assertEquals(descriptor.totalBytes, snapshot.contiguousBytes)

                // New wiring (R1.4): no coordinator in this harness, answer authors directly.
                // This sends COMPLETE to the direct author and both relay forwards.
                bobRouter.files.endIncoming(fileId, FileReceiveEnd.Ready)

                val finalPath = assertNotNull(bobFiles.localFilePath(fileId))
                val stored = files.openPositionedReader(finalPath).use { reader ->
                    val out = ByteArray(original.size)
                    var got = 0
                    while (got < out.size) {
                        val n = reader.readAt(got.toLong(), out, got, out.size - got)
                        check(n > 0)
                        got += n
                    }
                    out
                }
                assertContentEquals(original, stored)

                // Forget after delivery: both relay copies are gone, and the sender's
                // seal resolved DELIVERED on the target's confirmation.
                withTimeout(120.seconds) {
                    while (relay1Store.all().isNotEmpty() || relay2Store.all().isNotEmpty()) {
                        delay(100.milliseconds)
                    }
                }
                assertTrue(relay1Store.all().isEmpty() && relay2Store.all().isEmpty())
                // Both relays actually streamed chunks (a row dropped by a COMPLETE alone
                // would not prove it served any bytes).
                // Forward transfer ids differ from inbound ids; count any chunks seen.
                assertTrue(forwardedChunks.values.sum() > 0, "relays streamed chunks")
                // The seal-level COMPLETE reaches the origin once; a second Ready answers nothing.
                withTimeout(60.seconds) {
                    while (completeCount < 1) {
                        delay(100.milliseconds)
                    }
                }
                delay(2000.milliseconds)
                assertEquals(1, completeCount, "exactly one COMPLETE for the seal")
                // The sender's seal resolved DELIVERED on the target's confirmation.
                withTimeout(60.seconds) {
                    while (
                        aliceSealStore.byFile(fileId)
                            .none { it.status == FileSealStatus.DELIVERED }
                    ) {
                        delay(100.milliseconds)
                    }
                }
                assertTrue(
                    aliceSealStore.byFile(fileId)
                        .any { it.status == FileSealStatus.DELIVERED },
                )
            } finally {
                clockJob.cancel()
                pump.cancel()
                engineScope.cancel()
            }
        } finally {
            runCatching { aliceRouter.stop() }
            runCatching { relay1Router.stop() }
            runCatching { relay2Router.stop() }
            runCatching { bobRouter.stop() }
            fun deleteRecursively(path: Path) {
                if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
                    SystemFileSystem.list(path).forEach { deleteRecursively(it) }
                }
                SystemFileSystem.delete(path, mustExist = false)
            }
            runCatching { deleteRecursively(aliceWork) }
            runCatching { deleteRecursively(bobWork) }
        }
    }
}
