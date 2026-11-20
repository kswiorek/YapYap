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
import org.yapyap.orchestrator.file.DefaultFileDescriptorStore
import org.yapyap.orchestrator.file.DefaultFilePlaintextSource
import org.yapyap.orchestrator.file.DefaultFileReceiveEngine
import org.yapyap.orchestrator.file.DefaultFileReceiveStore
import org.yapyap.orchestrator.file.DefaultFileStore
import org.yapyap.orchestrator.file.DefaultPendingFileRequestStore
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.FileServiceConfig
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.runtime.file.DefaultFileTransferService
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.SignedAndEncryptedFileProtection
import org.yapyap.protection.envelope.SignedAndEncryptedMessageProtection
import org.yapyap.protection.envelope.SignedAndEncryptedWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedSystemProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.router.FakeFrontierSnapshotProvider
import org.yapyap.routing.router.FakeSyncPayloadProvider
import org.yapyap.routing.router.InMemoryFileIncomingTransferStore
import org.yapyap.routing.router.InMemoryFilePushStore
import org.yapyap.routing.router.InMemoryFileRelayStore
import org.yapyap.routing.router.InMemoryFileRequestAttemptStore
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InMemorySyncAttemptStore
import org.yapyap.routing.router.LocalLinkStatus
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.engineFileIngestPort
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.PrintingAppLogger
import org.yapyap.testfixtures.epochSeconds
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
 * Tier-3 (Phase C step 14): two live-Tor routers move a small file end to end.
 * Alice imports via the real [org.yapyap.orchestrator.file.FileStore], pushes through
 * her router's [org.yapyap.routing.router.Router.fileCarrier]; Bob's receive engine
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
            identity: org.yapyap.routing.router.E2eeIdentityResolverForRouter,
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
        ) = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = SignedAndEncryptedWebRtcSignalProtection(signer, manager, crypto),
            fileProtection = SignedAndEncryptedFileProtection(signer, manager, crypto),
            messageProtection = SignedAndEncryptedMessageProtection(signer, manager, crypto),
            systemProtection = SignedSystemProtection(signer, crypto),
            bootstrapProtection = BootstrapProtection(crypto) { null },
        )

        val aliceIdentity = org.yapyap.routing.router.E2eeIdentityResolverForRouter(
            local = alicePeer,
            peers = mapOf(bobPeer.device.deviceId to bobPeer),
            peersByAccount = mapOf(bobAccount to listOf(bobPeer.device.deviceId)),
            torByPeer = aliceTorMap,
            crypto = crypto,
        )
        val bobIdentity = org.yapyap.routing.router.E2eeIdentityResolverForRouter(
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
        val fileConfig = FileServiceConfig(
            fileCheckpointBytes = 16L * 1024L,
            fileCheckpointInterval = 200.milliseconds,
        )
        val chunkSize = 32 * 1024

        // Sender side: real store + import.
        val aliceDb = openMemoryDatabase().database
        val aliceDescriptors = DefaultFileDescriptorStore(aliceDb)
        val aliceWork = Path(SystemTemporaryDirectory, "yapyap-file-alice-${Uuid.random()}")
        files.createDirectories(aliceWork)
        val aliceStoreDir = Path(aliceWork.toString(), "store")
        files.createDirectories(aliceStoreDir)
        val aliceFileStore = DefaultFileStore(aliceDescriptors, crypto, files, aliceStoreDir, fileConfig)

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
        val bobFileStore = DefaultFileStore(bobDescriptors, crypto, files, bobStoreDir, fileConfig)

        val bobIngest = DelegatingFileIngestPort()

        val aliceRouter = DefaultRouter(
            torTransport = aliceTorTransport,
            webRtcTransport = aliceWebRtc,
            localIdentity = aliceIdentity,
            localDeviceId = alicePeer.device.deviceId,
            devices = aliceIdentity,
            routes = aliceIdentity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            packetOutbox = TrackingPacketOutbox(),
            envelopeProtectionService = aliceProtection,
            clock = clock,
            routerConfig = MutableStateFlow(RouterConfig()),
            transportLimits = MutableStateFlow(testTransportLimits()),
            syncAttemptStore = InMemorySyncAttemptStore(),
            syncPayloadProvider = FakeSyncPayloadProvider(),
            frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
            peerAvailabilityStore = FakePeerAvailabilityStore(),
            fileIngestPort = RefusingFileIngestPort(),
            fileIncomingStore = InMemoryFileIncomingTransferStore(),
            filePushStore = InMemoryFilePushStore(),
            fileRelayStore = InMemoryFileRelayStore(),
            fileRequestAttemptStore = InMemoryFileRequestAttemptStore(),
            plaintextSource = DefaultFilePlaintextSource(aliceDescriptors, files, crypto),
            fileChunkSize = MutableStateFlow(chunkSize),
        )
        val bobRouter = DefaultRouter(
            torTransport = bobTorTransport,
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
            fileIngestPort = bobIngest,
            fileIncomingStore = InMemoryFileIncomingTransferStore(),
            filePushStore = InMemoryFilePushStore(),
            fileRelayStore = InMemoryFileRelayStore(),
            fileRequestAttemptStore = InMemoryFileRequestAttemptStore(),
            plaintextSource = DefaultFilePlaintextSource(bobDescriptors, files, crypto),
            fileChunkSize = MutableStateFlow(chunkSize),
        )
        val bobFiles = DefaultFileTransferService(
            bobReceives,
            bobDescriptors,
            files,
            DefaultPendingFileRequestStore(bobDb),
            bobMessages,
            bobRouter,
            clock,
        )
        val bobEngine = DefaultFileReceiveEngine(
            receives = bobReceives,
            descriptors = bobDescriptors,
            messages = bobMessages,
            crypto = crypto,
            files = files,
            storeDirectory = bobStoreDir,
            incomingDirectory = bobIncomingDir,
            config = fileConfig,
            fileStore = bobFileStore,
            carrier = bobRouter.fileCarrier,
            routerConfig = MutableStateFlow(RouterConfig()),
            clock = clock,
        )
        bobIngest.delegate = engineFileIngestPort(bobEngine)

        try {
            bobRouter.start()
            aliceRouter.start()
            // The engine checkpoint loop is infinite: its own scope, cancelled on teardown.
            val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            bobEngine.start(engineScope)
            try {
                withTimeout(5.minutes) {
                    aliceRouter.localLinkStatus.first { it == LocalLinkStatus.Online }
                    bobRouter.localLinkStatus.first { it == LocalLinkStatus.Online }
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
                val descriptor = aliceFileStore.import(
                    source = org.yapyap.orchestrator.file.FileImportSource.PathSource(sourcePath),
                    fileName = "small.bin",
                    mimeType = "application/octet-stream",
                    keepCopy = true,
                )
                val fileId = descriptor.fileId

                aliceRouter.fileCarrier.pushFile(fileId, bobAccount)

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
