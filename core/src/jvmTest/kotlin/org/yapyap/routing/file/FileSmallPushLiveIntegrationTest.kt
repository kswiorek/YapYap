package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.yapyap.routing.file.port.FileReceiveEnd
import org.yapyap.routing.link.TorPeerSessions
import org.yapyap.routing.presence.FakePeerAvailabilityStore
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.router.E2eeIdentityResolverForRouter
import org.yapyap.routing.router.FakeFrontierSnapshotProvider
import org.yapyap.routing.router.FakeSyncPayloadProvider
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InMemorySyncAttemptStore
import org.yapyap.routing.router.LocalLinkStatus
import org.yapyap.routing.router.RefusingFilePorts
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.store.DefaultFileIncomingStore
import org.yapyap.routing.store.DefaultFilePullAttemptStore
import org.yapyap.routing.store.DefaultFileSealStore
import org.yapyap.routing.store.DefaultFileTransferStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.PrintingAppLogger
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.testfixtures.importHeld
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.transport.DefaultTorTransport
import org.yapyap.transport.webrtc.backend.JvmWebRtcBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Live path: two live-Tor routers move a small file end to end.
 * Alice imports via the real [org.yapyap.orchestrator.file.FileStore], pushes through
 * her router's [org.yapyap.routing.router.Router.files]; Bob's receive engine
 * stages, checkpoints and completes; the GUI-facing
 * [org.yapyap.orchestrator.runtime.file.FileTransferService] reports READY with the
 * identical fileId and the stored bytes match.
 *
 * Opt-in: `./gradlew :core:jvmTest -PintegrationTests=true`
 */
class FileSmallPushLiveIntegrationTest {

    @BeforeTest
    fun installLogger() = AppLog.init(PrintingAppLogger())

    @AfterTest
    fun resetLogger() = AppLog.reset()

    @Test
    fun twoRouters_liveTor_smallFilePush_reachesReadyWithIdenticalFileId() = runBlocking {
        val crypto = DefaultCryptoProvider()
        val alicePeer = buildTestPeerIdentity(crypto, "file-live-alice")
        val bobPeer = buildTestPeerIdentity(crypto, "file-live-bob")
        val bobAccount = AccountId("bob-file-live-account")
        val aliceAccount = AccountId("alice-file-live-account")

        val aliceTorDir = Path(SystemTemporaryDirectory, "yapyap-file-alice-tor-${Uuid.random()}")
        val bobTorDir = Path(SystemTemporaryDirectory, "yapyap-file-bob-tor-${Uuid.random()}")
        SystemFileSystem.createDirectories(aliceTorDir)
        SystemFileSystem.createDirectories(bobTorDir)
        val torConfig = MutableStateFlow(TorBackendConfig(startupTimeout = 180.seconds))
        val webRtcConfig = MutableStateFlow(WebRtcBackendConfig())

        val aliceTorBackend = KmpTorBackend(torStateRootPath = aliceTorDir, config = torConfig)
        val bobTorBackend = KmpTorBackend(torStateRootPath = bobTorDir, config = torConfig)
        val aliceTorTransport = DefaultTorTransport(aliceTorBackend)
        val bobTorTransport = DefaultTorTransport(bobTorBackend)
        val aliceWebRtc = DefaultWebRtcTransport(JvmWebRtcBackend(webRtcConfig))
        val bobWebRtc = DefaultWebRtcTransport(JvmWebRtcBackend(webRtcConfig))

        val aliceTorMap = mutableMapOf<PeerId, TorEndpoint>()
        val bobTorMap = mutableMapOf<PeerId, TorEndpoint>()
        val clock = FakeClock(epochSeconds(10_000L))

        fun sessionManager(
            identity: E2eeIdentityResolverForRouter,
        ) = DefaultCryptoSessionManager(
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
            peers = mapOf(bobPeer.device.deviceId to bobPeer),
            peersByAccount = mapOf(bobAccount to listOf(bobPeer.device.deviceId)),
            torByPeer = aliceTorMap,
            crypto = crypto,
        )
        val bobIdentity = E2eeIdentityResolverForRouter(
            local = bobPeer,
            peers = mapOf(alicePeer.device.deviceId to alicePeer),
            peersByAccount = mapOf(aliceAccount to listOf(alicePeer.device.deviceId)),
            torByPeer = bobTorMap,
            crypto = crypto,
        )
        val aliceSigner = DefaultSignatureProvider(aliceIdentity, aliceIdentity, crypto)
        val bobSigner = DefaultSignatureProvider(bobIdentity, bobIdentity, crypto)
        val aliceProtection = protection(sessionManager(aliceIdentity), aliceSigner)
        val bobProtection = protection(sessionManager(bobIdentity), bobSigner)

        val files = JvmFileAccess()
        val fileConfig = MutableStateFlow(
            OrchestratorConfig(
                fileCheckpointBytes = 16L * 1024L,
                fileCheckpointInterval = 200.milliseconds,
            ),
        )
        val chunkSize = 64 * 1024

        // Sender side: real store + import.
        val aliceDb = openMemoryDatabase().database
        val aliceDescriptors = DefaultFileDescriptorStore(aliceDb)
        val aliceWork = Path(SystemTemporaryDirectory, "yapyap-file-alice-${Uuid.random()}")
        files.createDirectories(aliceWork)
        val aliceStoreDir = Path(aliceWork.toString(), "store")
        files.createDirectories(aliceStoreDir)
        val aliceFileStore = DefaultFileStore(aliceDescriptors, crypto, files, aliceStoreDir)

        // Receiver side: real receive engine + GUI facade.
        val bobDb = openMemoryDatabase().database
        val bobReceives = DefaultFileReceiveStore(bobDb)
        val bobDescriptors = DefaultFileDescriptorStore(bobDb)
        val bobMessages = DefaultMessageRepository(bobDb)
        val bobWork = Path(SystemTemporaryDirectory, "yapyap-file-bob-${Uuid.random()}")
        files.createDirectories(bobWork)
        val bobStoreDir = Path(bobWork.toString(), "store")
        val bobIncomingDir = Path(bobWork.toString(), "incoming")
        files.createDirectories(bobStoreDir)
        files.createDirectories(bobIncomingDir)
        val bobFileStore = DefaultFileStore(bobDescriptors, crypto, files, bobStoreDir)

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

        fun buildFileRouter(
            identity: E2eeIdentityResolverForRouter,
            tor: org.yapyap.transport.tor.transport.TorTransport,
            webRtc: org.yapyap.transport.webrtc.transport.WebRtcTransport,
            protection: EnvelopeProtections,
            ingest: org.yapyap.routing.file.port.FileIngestPort,
            plaintext: org.yapyap.routing.file.port.FilePlaintextSource,
            db: YapYapDatabase,
        ): DefaultRouter {
            val torPeers = TorPeerSessions(identity, tor)
            val ctx = RoutingContext(
                localIdentity = identity,
                localDeviceId = identity.localDeviceId,
                devices = identity,
                torTransport = tor,
                webRtcTransport = webRtc,
                torPeers = torPeers,
                transportLimits = MutableStateFlow(testTransportLimits()),
                fileLimits = MutableStateFlow(FileLimits(chunkSize, chunkSize, maxSealBytes = 64 * 1024)),
                envelopeProtections = protection,
                clock = clock,
                routerConfig = MutableStateFlow(RouterConfig()),
            )
            return DefaultRouter(
                ctx = ctx,
                routes = identity,
                packetDeduplicator = InMemoryPacketDeduplicator(),
                packetOutbox = TrackingPacketOutbox(),
                peerAvailabilityStore = FakePeerAvailabilityStore(),
                syncAttemptStore = InMemorySyncAttemptStore(),
                syncPayloadProvider = FakeSyncPayloadProvider(),
                frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
                fileIngest = ingest,
                fileRequests = RefusingFilePorts(),
                filePlaintext = plaintext,
                fileSealStore = DefaultFileSealStore(db),
                fileTransferStore = DefaultFileTransferStore(db),
                fileIncomingStore = DefaultFileIncomingStore(db),
                filePullAttemptStore = DefaultFilePullAttemptStore(db),
            )
        }

        val aliceRouter = buildFileRouter(
            aliceIdentity, aliceTorTransport, aliceWebRtc, aliceProtection,
            RefusingFilePorts(),
            DefaultFilePlaintextSource(aliceDescriptors, aliceFileStore, files, crypto),
            aliceDb,
        )
        val bobRouter = buildFileRouter(
            bobIdentity, bobTorTransport, bobWebRtc, bobProtection,
            bobEngine,
            DefaultFilePlaintextSource(bobDescriptors, bobFileStore, files, crypto),
            bobDb,
        )
        // Bob only reads receive state here; accept and decline are not exercised.
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
            bobRouter.start()
            aliceRouter.start()
            // The engine checkpoint loop is infinite: its own scope, cancelled on teardown.
            val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            bobEngine.start(engineScope)
            try {
                withTimeout(5.minutes) {
                    aliceRouter.presence.localLinkStatus.first { it == LocalLinkStatus.Online }
                    bobRouter.presence.localLinkStatus.first { it == LocalLinkStatus.Online }
                }
                aliceTorMap[bobPeer.device.deviceId] =
                    bobIdentity.resolveTorEndpointForDevice(bobPeer.device.deviceId)!!
                bobTorMap[alicePeer.device.deviceId] =
                    aliceIdentity.resolveTorEndpointForDevice(alicePeer.device.deviceId)!!
                // Give LinkKeeper a chance to open sessions before the first push: with a
                // frozen FakeClock a parked first attempt would never become due again.
                delay(3.seconds)

                // Small file: 96 KiB of deterministic bytes -> 3 chunks at 32 KiB.
                val original = ByteArray(96 * 1024) { (it * 7).toByte() }
                val sourcePath = Path(aliceWork.toString(), "small.bin")
                files.openAppendWriter(sourcePath, null).use {
                    it.write(original)
                    it.flushAndSync()
                }
                val descriptor = aliceFileStore.importHeld(
                    source = FileImportSource.PathSource(sourcePath),
                    fileName = "small.bin",
                    mimeType = "application/octet-stream",
                    keepCopy = true,
                )
                val fileId = descriptor.fileId

                aliceRouter.files.pushFile(fileId, bobAccount)

                val maybeSnapshot = withTimeout(240.seconds) {
                    var latest = bobFiles.receiveState(fileId)
                    while (latest?.status != FileReceiveStatus.READY) {
                        delay(1.seconds)
                        // Keep the frozen FakeClock moving so parked outbox rows become due
                        // and retry once the Tor links are open.
                        clock.advanceBy(5.seconds)
                        latest = bobFiles.receiveState(fileId)
                    }
                    latest
                }
                val snapshot = assertNotNull(maybeSnapshot, "receive must reach READY")
                assertEquals(fileId, snapshot.fileId)
                assertEquals(FileReceiveStatus.READY, snapshot.status)
                assertEquals(descriptor.totalBytes, snapshot.totalBytes)
                assertEquals(descriptor.totalBytes, snapshot.contiguousBytes)

                // New wiring (R1.4): no coordinator in this harness, answer authors directly.
                bobRouter.files.endIncoming(fileId, FileReceiveEnd.Ready)

                val finalPath = assertNotNull(
                    bobFiles.localFilePath(fileId),
                    "READY file must vend a local path",
                )
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
            } finally {
                engineScope.cancel()
            }
        } finally {
            runCatching { aliceRouter.stop() }
            runCatching { bobRouter.stop() }
            runCatching { deleteRecursively(aliceTorDir) }
            runCatching { deleteRecursively(bobTorDir) }
            runCatching { deleteRecursively(aliceWork) }
            runCatching { deleteRecursively(bobWork) }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}
