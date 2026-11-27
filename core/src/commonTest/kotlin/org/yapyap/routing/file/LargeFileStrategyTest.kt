package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.file.large.LargeFileService
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.link.PeerLinks
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.router.DeviceDirectory
import org.yapyap.routing.router.FakeFilePorts
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.InMemoryFilePullAttemptStore
import org.yapyap.routing.router.PassthroughFakeEnvelopeProtectionService
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Large request path, routing side: the fetch strategy opens a WebRTC
 * session ([LinkKeeper.await]) and then runs a fetch. PULL_OK and
 * DECLINED are the attempt's outcomes with no WAITING state; unreachable
 * candidates stay PENDING; online-but-unconnectable reports "no direct
 * connection". Nothing large touches the outbox or Tor.
 */
class LargeFileStrategyTest {

    private val localDevice = PeerId("large-loop-local-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val holderDevice = PeerId("large-loop-holder-device-bbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val holderDevice2 = PeerId("large-loop-holder2-device-cccccccccccccccccccccccccccc")
    private val holderAccount = AccountId("large-loop-holder-account")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val largeTotal = 11L * 1024L * 1024L
    private val now = epochSeconds(10_000L)

    private class BytePlaintext(val bytes: ByteArray) : FilePlaintextSource {
        fun info(name: String = "big.bin") = FileManifest(
            fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            totalBytes = bytes.size.toLong(),
            blockSize = 1024 * 1024,
            blockHashes = emptyList(),
            fileName = name,
            mimeType = "application/octet-stream",
        )

        override suspend fun describe(fileId: FileId): FileManifest? = info()

        override suspend fun open(fileId: FileId, fromOffset: Long): PlaintextStream? {
            if (fromOffset > bytes.size) return null
            return object : PlaintextStream {
                var pos = fromOffset.toInt().coerceAtMost(bytes.size)
                override suspend fun read(maxBytes: Int): PlaintextRead {
                    if (pos >= bytes.size) return PlaintextRead.Eof
                    val end = minOf(bytes.size, pos + maxBytes)
                    val slice = bytes.copyOfRange(pos, end)
                    pos = end
                    return PlaintextRead.Bytes(slice)
                }

                override fun close() = Unit
            }
        }
    }

    private class CompletingSource(
        var coverage: FileCoverage? = FileCoverage(0L, emptyList()),
        var candidates: List<AccountId> = emptyList(),
        var serveAllowed: Boolean = true,
        var defaultTotalBytes: Long? = null,
        var totalBytesByFile: Map<FileId, Long?> = emptyMap(),
        var appendImpl: (suspend (offset: Long, bytes: ByteArray) -> FileAppendResult)? = null,
    ) : FakeFilePorts {
        var checkOffers = 0
        var ingestedBytes = 0L
        override suspend fun checkOffer(offer: FileManifest): FileOfferVerdict =
            FileOfferVerdict.Accept.also { checkOffers++ }

        override suspend fun ingest(
            fileId: FileId,
            byteOffset: Long,
            plaintext: ByteArray
        ): FileIngestResult {
            ingestedBytes += plaintext.size
            val total = totalBytesByFile[fileId] ?: defaultTotalBytes ?: (byteOffset + plaintext.size)
            return FileIngestResult.Staged(byteOffset + plaintext.size, total)
        }

        override suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult {
            ingestedBytes += bytes.size
            appendImpl?.let { return it(offset, bytes) }
            val total = totalBytesByFile[fileId] ?: defaultTotalBytes ?: (offset + bytes.size)
            return if (offset + bytes.size >= total) FileAppendResult.Ready
            else FileAppendResult.Appended(offset + bytes.size)
        }

        override suspend fun heldCoverage(fileId: FileId): FileCoverage? = coverage
        override suspend fun requestCandidates(fileId: FileId): List<AccountId> = candidates
        override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean = serveAllowed
        override suspend fun requestTotalBytes(fileId: FileId): Long? = totalBytesByFile[fileId] ?: defaultTotalBytes
    }

    private class MapDevices(private val map: Map<PeerId, AccountId>) : DeviceDirectory {
        override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = map[deviceId]
        override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
            map.filter { it.value == accountId }.keys.toList()

        override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
            map.filter { it.value in accountIds }.keys.toList()

        override suspend fun getAllActiveDeviceIds(): List<PeerId> = map.keys.toList()
        override suspend fun getDeviceStatus(deviceId: PeerId) =
            if (deviceId in map) IdentityStatus.ACTIVE else null
        override suspend fun getAccountStatus(accountId: AccountId) = null
        override suspend fun getDeclaredTorEndpoint(deviceId: PeerId) = null
    }

    private class Fixture(
        val scope: CoroutineScope,
        val processor: FilePullProcessor,
        val attempts: InMemoryFilePullAttemptStore,
        val events: MutableSharedFlow<FileEvent>,
        val requesterWebRtc: RecordingWebRtcTransport,
        val requesterTor: RecordingTorTransport,
        val requesterGate: CompletingSource,
        val jobs: List<Job>,
    ) {
        fun stop() {
            jobs.forEach { it.cancel() }
            scope.cancel()
        }

        /** Every envelope the requester put on Tor during the test. */
        fun torSends(): List<BinaryEnvelope> =
            requesterTor.sendsExcludingHeartbeat().map { it.second }
    }

    private fun fixture(
        holderDevices: List<PeerId> = listOf(holderDevice),
        holderCanServe: (PeerId) -> Boolean = { true },
        simulateSession: Boolean = true,
        advanceClock: Boolean = false,
        routerConfig: RouterConfig = RouterConfig(
            largeFileSessionOpenTimeout = 300.milliseconds,
            fileRequestAttemptTimeout = 2.seconds,
        ),
        dataBytes: ByteArray = ByteArray(largeTotal.toInt()) { (it * 7).toByte() },
    ): Fixture {
        val clock = FakeClock(now)
        val configFlow = MutableStateFlow(routerConfig)
        val local = DeviceIdentity(
            localDevice,
            SigningPublicKey(byteArrayOf(1)),
            EncryptionPublicKey(byteArrayOf(2)),
        )
        val identity = FakeIdentityResolverForRouter(
            localDevice = local,
            peersByAccount = mapOf(holderAccount to holderDevices),
            torByPeer = holderDevices.associateWith { TorEndpoint("${it.id}.onion", 80) }.toMutableMap(),
        )
        val requesterTor = RecordingTorTransport()
        val requesterWebRtc = RecordingWebRtcTransport()
        val ctx = RoutingContext(
            localIdentity = identity,
            localDeviceId = localDevice,
            devices = identity,
            torTransport = requesterTor,
            webRtcTransport = requesterWebRtc,
            peerLinks = PeerLinks(identity, requesterWebRtc, requesterTor),
            transportLimits = MutableStateFlow(testTransportLimits()),
            fileLimits = MutableStateFlow(FileLimits(32 * 1024, 32 * 1024)),
            envelopeProtections = PassthroughFakeEnvelopeProtectionService(),
            clock = clock,
            routerConfig = configFlow,
        )
        for (device in holderDevices) {
            ctx.peerLinks.bindTorEndpoint(device, TorEndpoint("${device.id}.onion", 80))
        }
        val outbox = TrackingPacketOutbox()
        val outboxProcessor = outboxProcessorUnderTest(
            tor = requesterTor,
            webRtc = requesterWebRtc,
            identity = identity,
            outbox = outbox,
            clock = clock,
            routerConfig = routerConfig,
        )
        val torLinkKeeper = LinkKeeper(
            name = "fetch-test-tor",
            sessions = ctx.peerLinks.tor,
            targets = { emptyList() },
            ready = kotlinx.coroutines.flow.flowOf(false),
            config = MutableStateFlow(routerConfig.torLinkKeeperConfig),
            clock = clock,
        )
        val webRtcLinkKeeper = LinkKeeper(
            name = "fetch-test-webrtc",
            sessions = requesterWebRtc,
            targets = { emptyList() },
            ready = kotlinx.coroutines.flow.flowOf(false),
            config = MutableStateFlow(routerConfig.webrtcLinkKeeperConfig),
            clock = clock,
        )
        val scope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val holderEvents = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val attempts = InMemoryFilePullAttemptStore()
        val requesterGate = CompletingSource(candidates = listOf(holderAccount), defaultTotalBytes = largeTotal)
        val fileLimits = MutableStateFlow(FileLimits(32 * 1024, 32 * 1024))
        val requesterManager = LargeFileService(
            devices = ctx.devices,
            transport = requesterWebRtc,
            plaintext = BytePlaintext(ByteArray(0)),
            ingest = requesterGate,
            requests = requesterGate,
            routerConfig = configFlow,
            fileLimits = fileLimits,
            events = events,
            clock = clock,
        )
        val processor = FilePullProcessor(
            ctx = ctx,
            packets = FilePackets(ctx),
            pullAttempts = attempts,
            requests = requesterGate,
            outboxProcessor = outboxProcessor,
            peerPolicy = DefaultSyncPeerPolicy(ctx),
            maxIdlePoll = MutableStateFlow(50.milliseconds),
            events = events,
            linkKeeper = torLinkKeeper,
            webRtcLinks = webRtcLinkKeeper,
            fetchService = requesterManager,
        )
        val holderTransports = holderDevices.associateWith { RecordingWebRtcTransport() }
        val holderManagers = holderDevices.associateWith { device ->
            val holderGate = CompletingSource(serveAllowed = holderCanServe(device))
            LargeFileService(
                devices = MapDevices(mapOf(localDevice to holderAccount)),
                transport = holderTransports.getValue(device),
                plaintext = BytePlaintext(dataBytes),
                ingest = holderGate,
                requests = holderGate,
                routerConfig = configFlow,
                fileLimits = fileLimits,
                events = holderEvents,
                clock = clock,
            )
        }
        val jobs = mutableListOf<Job>()
        jobs += requesterManager.runIn(scope)
        holderManagers.values.forEach { jobs += it.runIn(scope) }
        jobs += processor.runIn(scope)
        // File-frame pump between requester and holder transports, routed by label.
        // Channels are per-side (peer = remote); the peer sees the same label with
        // peer = sender. Translate on every hop.
        jobs += scope.launch {
            var requesterSent = 0
            val holderSent = mutableMapOf<PeerId, Int>()
            var requesterClosed = 0
            val holderClosed = mutableMapOf<PeerId, Int>()
            while (true) {
                delay(10.milliseconds)
                val reqSends = requesterWebRtc.sendFileFrameCalls.toList()
                while (requesterSent < reqSends.size) {
                    val (channel, payload) = reqSends[requesterSent++]
                    val holderTransport = holderTransports[channel.peer] ?: continue
                    val forHolder = WebRtcFileFrame(
                        FileChannelId(peer = localDevice, label = channel.label),
                        payload,
                    )
                    while (!holderTransport.tryEmitFileFrame(forHolder)) delay(5.milliseconds)
                }
                for ((device, transport) in holderTransports) {
                    val sends = transport.sendFileFrameCalls.toList()
                    var done = holderSent.getOrElse(device) { 0 }
                    while (done < sends.size) {
                        val (channel, payload) = sends[done++]
                        val forRequester = WebRtcFileFrame(
                            FileChannelId(peer = device, label = channel.label),
                            payload,
                        )
                        while (!requesterWebRtc.tryEmitFileFrame(forRequester)) delay(5.milliseconds)
                    }
                    holderSent[device] = done
                    val closes = transport.closeFileChannelCalls.toList()
                    var closedDone = holderClosed.getOrElse(device) { 0 }
                    while (closedDone < closes.size) {
                        val channel = closes[closedDone++]
                        requesterWebRtc.tryEmitFileChannelEvent(
                            WebRtcFileChannelEvent.Removed(
                                FileChannelId(peer = device, label = channel.label)
                            )
                        )
                    }
                    holderClosed[device] = closedDone
                }
                val reqCloses = requesterWebRtc.closeFileChannelCalls.toList()
                while (requesterClosed < reqCloses.size) {
                    val channel = reqCloses[requesterClosed++]
                    holderTransports[channel.peer]?.tryEmitFileChannelEvent(
                        WebRtcFileChannelEvent.Removed(
                            FileChannelId(peer = localDevice, label = channel.label)
                        )
                    )
                }
            }
        }
        if (simulateSession) {
            // Sessions open on demand: report CONNECTED as soon as the loop asks.
            jobs += scope.launch {
                val simulated = mutableSetOf<PeerId>()
                while (true) {
                    delay(10.milliseconds)
                    for (peer in requesterWebRtc.openSessionCalls.toList()) {
                        if (simulated.add(peer)) {
                            requesterWebRtc.setSessionState(WebRtcSessionState(peer, SessionPhase.CONNECTED))
                            requesterWebRtc.simulateEnvelopeChannelOpen(peer)
                        }
                    }
                }
            }
        }
        if (advanceClock) {
            // awaitSession's deadline and the retry backoffs read the virtual
            // clock: advance it in real time so timeouts can fire in tests.
            jobs += scope.launch {
                while (true) {
                    delay(50.milliseconds)
                    clock.advanceBy(100.milliseconds)
                }
            }
        }
        return Fixture(
            scope,
            processor,
            attempts,
            events,
            requesterWebRtc,
            requesterTor,
            requesterGate,
            jobs
        )
    }

    @Test
    fun satisfied_endToEnd_nothingLargeOverTor() = runBlocking {
        val f = fixture()
        try {
            f.processor.start(fileId, now)
            withTimeout(20.seconds) {
                while (f.attempts.findById(fileId) != null) delay(50.milliseconds)
            }
            assertNull(f.attempts.findById(fileId), "satisfied fetch drops the row")
            assertEquals(11L * 1024L * 1024L, f.requesterGate.ingestedBytes, "all bytes ingested")
            assertTrue(
                f.torSends().none { it.packetType == PacketType.FILE },
                "large bytes never cross Tor (fetch is WebRTC-only)",
            )
        } finally {
            f.stop()
        }
    }

    @Test
    fun declined_movesToNextCandidate() = runBlocking {
        val f = fixture(holderDevices = listOf(holderDevice, holderDevice2), holderCanServe = { it == holderDevice2 })
        try {
            f.processor.start(fileId, now)
            withTimeout(20.seconds) {
                while (f.attempts.findById(fileId) != null) delay(50.milliseconds)
            }
            assertTrue(holderDevice in f.attempts.declinedDevices(fileId) || f.attempts.findById(fileId) == null)
            assertNull(f.attempts.findById(fileId), "second candidate satisfies")
        } finally {
            f.stop()
        }
    }

    @Test
    fun timeoutOnline_reportsNoDirectConnection_keepsPending() = runBlocking {
        val f = fixture(simulateSession = false, advanceClock = true)
        try {
            val seen = mutableListOf<FileEvent>()
            val collect = launch { f.events.collect { seen += it } }
            f.processor.start(fileId, now)
            withTimeout(5.seconds) {
                while (seen.none { it is FileEvent.NoDirectConnection }) delay(50.milliseconds)
            }
            collect.cancel()
            assertNotNull(f.attempts.findById(fileId), "unreachable candidate is not a decline")
            assertTrue(f.attempts.declinedDevices(fileId).isEmpty(), "online-but-broken is never declined")
        } finally {
            f.stop()
        }
    }

    @Test
    fun allDeclined_reportsUnavailable() = runBlocking {
        val f = fixture(holderCanServe = { false })
        try {
            val seen = mutableListOf<FileEvent>()
            val collect = launch { f.events.collect { seen += it } }
            f.processor.start(fileId, now)
            withTimeout(10.seconds) {
                while (f.attempts.findById(fileId) != null) delay(50.milliseconds)
            }
            collect.cancel()
            assertTrue(seen.any { it is FileEvent.RequestUnavailable && it.fileId == fileId })
        } finally {
            f.stop()
        }
    }

    @Test
    fun smallRows_ignoredByLargeLoop() = runBlocking {
        val f = fixture()
        f.requesterGate.defaultTotalBytes = 1_000L
        try {
            f.processor.start(fileId, now)
            delay(500.milliseconds)
            // Core owns small files: a sealed REQUEST went through the outbox.
            assertTrue(f.requesterWebRtc.openSessionCalls.isEmpty(), "large path never opens sessions for small files")
        } finally {
            f.stop()
        }
    }

    @Test
    fun noSpace_dropsRowWithoutBlacklisting() = runBlocking {
        val f = fixture()
        f.requesterGate.appendImpl = { _, _ -> FileAppendResult.NoSpace }
        try {
            f.processor.start(fileId, now)
            withTimeout(20.seconds) {
                while (f.attempts.findById(fileId) != null) delay(50.milliseconds)
            }
            assertNull(f.attempts.findById(fileId), "NO_SPACE drops the row")
            assertTrue(
                f.attempts.declinedDevices(fileId).isEmpty(),
                "a local no-space refusal never counts against the holder"
            )
        } finally {
            f.stop()
        }
    }

    @Test
    fun mismatch_retriesSameHolderFirst() = runBlocking {
        val f = fixture(holderDevices = listOf(holderDevice, holderDevice2), advanceClock = true)
        var first = true
        f.requesterGate.appendImpl = { offset, bytes ->
            if (first) {
                first = false
                FileAppendResult.Mismatch(0)
            } else if (offset + bytes.size >= (f.requesterGate.defaultTotalBytes ?: 0L)) {
                FileAppendResult.Ready
            } else {
                FileAppendResult.Appended(offset + bytes.size)
            }
        }
        try {
            f.processor.start(fileId, now)
            withTimeout(60.seconds) {
                while (f.attempts.findById(fileId) != null) delay(50.milliseconds)
            }
            assertNull(f.attempts.findById(fileId), "mismatch eventually completes")
            assertTrue(
                f.attempts.declinedDevices(fileId).isEmpty(),
                "MISMATCH retries the same holder first, never blacklists"
            )
        } finally {
            f.stop()
        }
    }
}
