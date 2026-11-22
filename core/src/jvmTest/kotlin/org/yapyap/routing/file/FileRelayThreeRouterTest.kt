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
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protection.PassthroughFileProtection
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.SignedAndEncryptedMessageProtection
import org.yapyap.protection.envelope.SignedAndEncryptedWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedSystemProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReceiveEnds
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.router.E2eeIdentityResolverForRouter
import org.yapyap.routing.router.FakeFilePlaintextSource
import org.yapyap.routing.router.FakeFrontierSnapshotProvider
import org.yapyap.routing.router.FakeSyncPayloadProvider
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InMemorySyncAttemptStore
import org.yapyap.routing.router.NoFileReceiveEnds
import org.yapyap.routing.router.RefusingFilePorts
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.store.DefaultFileIncomingTransferStore
import org.yapyap.routing.store.DefaultFilePushStore
import org.yapyap.routing.store.DefaultFileRelayStore
import org.yapyap.routing.store.DefaultFileRequestAttemptStore
import org.yapyap.routing.store.FileIncomingTransferStore
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.FilePushStore
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.sync.FakePeerAvailabilityStore
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
        ) = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = SignedAndEncryptedWebRtcSignalProtection(signer, manager, crypto),
            messageProtection = SignedAndEncryptedMessageProtection(signer, manager, crypto),
            systemProtection = SignedSystemProtection(signer, crypto),
            bootstrapProtection = BootstrapProtection(crypto) { null },
        )

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
        val alicePushes = DefaultFilePushStore(aliceDb)
        val aliceOutbox = TrackingPacketOutbox()
        val relay1Store = DefaultFileRelayStore(relay1Db)
        val relay2Store = DefaultFileRelayStore(relay2Db)
        val bobIncoming = DefaultFileIncomingTransferStore(bobDb)

        fun filePorts(
            plaintext: FilePlaintextSource,
            ingest: FileIngestPort,
            receiveEnds: FileReceiveEnds,
            pushes: FilePushStore,
            relay: FileRelayStore,
            incomingOverride: FileIncomingTransferStore? = null,
        ) = FileRoutingPorts(
            ingest = ingest,
            requests = RefusingFilePorts(),
            serveGate = RefusingFilePorts(),
            plaintext = plaintext,
            receiveEnds = receiveEnds,
            incoming = incomingOverride ?: DefaultFileIncomingTransferStore(aliceDb),
            pushes = pushes,
            relay = relay,
            requestAttempts = DefaultFileRequestAttemptStore(aliceDb),
            protection = PassthroughFileProtection(),
            chunkSizeBytes = MutableStateFlow(chunkSize),
            fetchFrameBytes = MutableStateFlow(chunkSize),
        )

        val aliceRouter = DefaultRouter(
            torTransport = aliceTor,
            webRtcTransport = aliceWebRtc,
            localIdentity = aliceIdentity,
            localDeviceId = alicePeer.device.deviceId,
            devices = aliceIdentity,
            routes = aliceIdentity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            packetOutbox = aliceOutbox,
            envelopeProtectionService = aliceProtection,
            clock = clock,
            routerConfig = MutableStateFlow(senderRouterConfig),
            transportLimits = MutableStateFlow(testTransportLimits()),
            syncAttemptStore = InMemorySyncAttemptStore(),
            syncPayloadProvider = FakeSyncPayloadProvider(),
            frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
            peerAvailabilityStore = aliceAvailability,
            filePorts = filePorts(
                DefaultFilePlaintextSource(aliceDescriptors, aliceFileStore, files, crypto),
                RefusingFilePorts(),
                NoFileReceiveEnds,
                alicePushes,
                DefaultFileRelayStore(aliceDb),
            ),
        )
        val relay1Router = DefaultRouter(
            torTransport = relay1Tor,
            webRtcTransport = relay1WebRtc,
            localIdentity = relay1Identity,
            localDeviceId = relay1Peer.device.deviceId,
            devices = relay1Identity,
            routes = relay1Identity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            packetOutbox = TrackingPacketOutbox(),
            envelopeProtectionService = relay1Protection,
            clock = clock,
            routerConfig = MutableStateFlow(relayRouterConfig),
            transportLimits = MutableStateFlow(testTransportLimits()),
            syncAttemptStore = InMemorySyncAttemptStore(),
            syncPayloadProvider = FakeSyncPayloadProvider(),
            frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
            peerAvailabilityStore = FakePeerAvailabilityStore(),
            filePorts = filePorts(
                FakeFilePlaintextSource(),
                RefusingFilePorts(),
                NoFileReceiveEnds,
                DefaultFilePushStore(relay1Db),
                relay1Store,
            ),
        )
        val relay2Router = DefaultRouter(
            torTransport = relay2Tor,
            webRtcTransport = relay2WebRtc,
            localIdentity = relay2Identity,
            localDeviceId = relay2Peer.device.deviceId,
            devices = relay2Identity,
            routes = relay2Identity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            packetOutbox = TrackingPacketOutbox(),
            envelopeProtectionService = relay2Protection,
            clock = clock,
            routerConfig = MutableStateFlow(relayRouterConfig),
            transportLimits = MutableStateFlow(testTransportLimits()),
            syncAttemptStore = InMemorySyncAttemptStore(),
            syncPayloadProvider = FakeSyncPayloadProvider(),
            frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
            peerAvailabilityStore = FakePeerAvailabilityStore(),
            filePorts = filePorts(
                FakeFilePlaintextSource(),
                RefusingFilePorts(),
                NoFileReceiveEnds,
                DefaultFilePushStore(relay2Db),
                relay2Store,
            ),
        )
        val bobRouter = DefaultRouter(
            torTransport = bobTor,
            webRtcTransport = bobWebRtc,
            localIdentity = bobIdentity,
            localDeviceId = bobPeer.device.deviceId,
            devices = bobIdentity,
            routes = bobIdentity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            packetOutbox = TrackingPacketOutbox(),
            envelopeProtectionService = bobProtection,
            clock = clock,
            routerConfig = MutableStateFlow(RouterConfig()),
            transportLimits = MutableStateFlow(testTransportLimits()),
            syncAttemptStore = InMemorySyncAttemptStore(),
            syncPayloadProvider = FakeSyncPayloadProvider(),
            frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
            peerAvailabilityStore = FakePeerAvailabilityStore(),
            filePorts = filePorts(
                DefaultFilePlaintextSource(bobDescriptors, bobFileStore, files, crypto),
                bobEngine,
                bobEngine,
                DefaultFilePushStore(bobDb),
                DefaultFileRelayStore(bobDb),
                incomingOverride = bobIncoming,
            ),
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
            val completeCounts = mutableMapOf<TransferId, Int>()
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
                                        val inner = FileEnvelope.decode(env.payload)
                                        val payload = inner.decodePayload()
                                        if (payload is FilePayload.EncryptedChunk) {
                                            forwardedChunks[inner.transferId] =
                                                (forwardedChunks[inner.transferId] ?: 0) + 1
                                        }
                                    }
                                }
                                if (dest == aliceTor) {
                                    runCatching {
                                        val inner = FileEnvelope.decode(env.payload)
                                        if (inner.decodePayload() is FilePayload.Complete) {
                                            completeCounts[inner.transferId] =
                                                (completeCounts[inner.transferId] ?: 0) + 1
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
                // (WHOLE is transient: the forward starts at once, so await full coverage
                // in a non-RESERVED row instead of the WHOLE status itself.)
                val expectedChunks = ((descriptor.totalBytes - 1) / chunkSize + 1).toInt()
                withTimeout(120.seconds) {
                    var n = 0
                    while (
                        relay1Store.live().none { row ->
                            row.status != FileRelayStatus.RESERVED &&
                                    relay1Store.chunkCount(row.transferId) >= expectedChunks
                        } ||
                        relay2Store.live().none { row ->
                            row.status != FileRelayStatus.RESERVED &&
                                    relay2Store.chunkCount(row.transferId) >= expectedChunks
                        }
                    ) {
                        delay(100.milliseconds)
                        if (++n % 50 == 0) {
                            println(
                                "DIAG outbox=" + aliceOutbox.enqueued.size +
                                        " sender=" + alicePushes.allByFile(fileId).map { it.kind to it.status } +
                                        " r1=" + relay1Store.live()
                                    .map { it.status to relay1Store.chunkCount(it.transferId) } +
                                        " r2=" + relay2Store.live()
                                    .map { it.status to relay2Store.chunkCount(it.transferId) },
                            )
                        }
                    }
                }
                val r1ForwardId = relay1Store.live().single().transferId
                val r2ForwardId = relay2Store.live().single().transferId

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
                // direct transfer resolved COMPLETED on the target's confirmation.
                withTimeout(120.seconds) {
                    while (relay1Store.live().isNotEmpty() || relay2Store.live().isNotEmpty()) {
                        delay(100.milliseconds)
                    }
                }
                assertTrue(relay1Store.live().isEmpty() && relay2Store.live().isEmpty())
                // Both relays actually streamed chunks (a row dropped by a COMPLETE alone
                // would not prove it served any bytes).
                assertTrue((forwardedChunks[r1ForwardId] ?: 0) > 0, "relay 1 streamed chunks")
                assertTrue((forwardedChunks[r2ForwardId] ?: 0) > 0, "relay 2 streamed chunks")
                // READY exactly once per author: each transfer author got exactly one COMPLETE.
                // A second Ready would find no incoming rows and answer nothing.
                val directId = alicePushes.allByFile(fileId)
                    .single { it.kind == FilePushKind.DIRECT }.transferId
                withTimeout(60.seconds) {
                    while (
                        (completeCounts[directId] ?: 0) < 1 ||
                        (completeCounts[r1ForwardId] ?: 0) < 1 ||
                        (completeCounts[r2ForwardId] ?: 0) < 1
                    ) {
                        delay(100.milliseconds)
                    }
                }
                delay(2000)
                assertEquals(1, completeCounts[directId], "exactly one COMPLETE for the direct transfer")
                assertEquals(1, completeCounts[r1ForwardId], "exactly one COMPLETE for relay 1's forward")
                assertEquals(1, completeCounts[r2ForwardId], "exactly one COMPLETE for relay 2's forward")
                // The sender's direct transfer resolved COMPLETED on the target's
                // confirmation (COMPLETED is terminal, so look beyond live rows).
                withTimeout(60.seconds) {
                    while (
                        alicePushes.allByFile(fileId)
                            .none { it.kind == FilePushKind.DIRECT && it.status == FilePushStatus.COMPLETED }
                    ) {
                        delay(100.milliseconds)
                    }
                }
                assertTrue(
                    alicePushes.allByFile(fileId)
                        .any { it.kind == FilePushKind.DIRECT && it.status == FilePushStatus.COMPLETED },
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
