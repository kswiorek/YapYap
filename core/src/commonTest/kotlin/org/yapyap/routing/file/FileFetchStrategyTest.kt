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
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.directory.DeviceDirectory
import org.yapyap.routing.file.fetch.FileFetchManager
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReadResult
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.router.InMemoryFileRequestAttemptStore
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RouterConfig
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.sync.buildSyncRoutingStack
import org.yapyap.sync.testDeviceIdentity
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.FakeFileSource
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcDataType
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Large request path, routing side: the fetch strategy opens a WebRTC session
 * ([ProactiveSessionOpener.awaitSession]) and then runs a fetch. FETCH_OK and
 * FETCH_DECLINED are the attempt's outcomes with no WAITING state; unreachable
 * candidates stay PENDING; online-but-unconnectable reports "no direct
 * connection". Nothing large touches the outbox or Tor.
 */
class FileFetchStrategyTest {

    private val localDevice = PeerId("large-loop-local-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val holderDevice = PeerId("large-loop-holder-device-bbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val holderDevice2 = PeerId("large-loop-holder2-device-cccccccccccccccccccccccccccc")
    private val holderAccount = AccountId("large-loop-holder-account")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val largeTotal = 11L * 1024L * 1024L
    private val now = epochSeconds(10_000L)

    private class Plaintext(val bytes: ByteArray) : FilePlaintextSource {
        fun info(name: String = "big.bin") = FileManifest(
            fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            totalBytes = bytes.size.toLong(),
            blockSize = 1024 * 1024,
            blockHashes = emptyList(),
            fileName = name,
            mimeType = "application/octet-stream",
        )

        override suspend fun describe(fileId: FileId): FileManifest? = info()

        override suspend fun read(fileId: FileId, offset: Long, maxBytes: Int): FileReadResult {
            if (offset >= bytes.size) return FileReadResult.Ok(ByteArray(0), eof = true)
            val end = minOf(bytes.size, (offset + maxBytes).toInt())
            return FileReadResult.Ok(bytes.copyOfRange(offset.toInt(), end), eof = end >= bytes.size)
        }
    }

    private class MapDevices(private val map: Map<PeerId, AccountId>) : DeviceDirectory {
        override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = map[deviceId]
        override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
            map.filter { it.value == accountId }.keys.toList()

        override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
            map.filter { it.value in accountIds }.keys.toList()

        override suspend fun getAllActiveDeviceIds(): List<PeerId> = map.keys.toList()
        override suspend fun getDeviceStatus(deviceId: PeerId) = null
        override suspend fun getAccountStatus(accountId: AccountId) = null
        override suspend fun getDeclaredTorEndpoint(deviceId: PeerId) = null
    }

    private data class Fixture(
        val scope: CoroutineScope,
        val processor: FileRequestProcessor,
        val attempts: InMemoryFileRequestAttemptStore,
        val events: MutableSharedFlow<FileEvent>,
        val holderEvents: MutableSharedFlow<FileEvent>,
        val requesterWebRtc: RecordingWebRtcTransport,
        val requesterTor: RecordingTorTransport,
        val holderTransports: Map<PeerId, RecordingWebRtcTransport>,
        val requesterGate: FakeFileSource,
        val holderData: ByteArray,
        val jobs: List<Job>,
    ) {
        fun stop() {
            jobs.forEach { it.cancel() }
            scope.cancel()
        }

        /** Every envelope the requester put on Tor during the test. */
        fun torSends(): List<org.yapyap.protocol.envelopes.BinaryEnvelope> =
            requesterTor.sendsExcludingHeartbeat().map { it.second }
    }

    private fun fixture(
        holderDevices: List<PeerId> = listOf(holderDevice),
        holderCanServe: (PeerId) -> Boolean = { true },
        simulateSession: Boolean = true,
        advanceClock: Boolean = false,
        routerConfig: RouterConfig = RouterConfig(
            fileSessionOpenTimeout = 300.milliseconds,
            fileRequestAttemptTimeout = 2.seconds,
        ),
        dataBytes: ByteArray = ByteArray(largeTotal.toInt()) { (it * 7).toByte() },
    ): Fixture {
        val clock = FakeClock(now)
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(holderAccount to holderDevices),
            torByPeer = holderDevices.associateWith { TorEndpoint("${it.id}.onion", 80) }.toMutableMap(),
            clock = clock,
        )
        for (device in holderDevices) {
            stack.ctx.peerLinks.bindTorEndpoint(device, TorEndpoint("${device.id}.onion", 80))
        }
        (stack.ctx.routerConfig as MutableStateFlow).value = routerConfig
        val configFlow = MutableStateFlow(routerConfig)
        val scope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val holderEvents = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val attempts = InMemoryFileRequestAttemptStore()
        val requesterGate = FakeFileSource(candidates = listOf(holderAccount), defaultTotalBytes = largeTotal)
        val requesterManager = FileFetchManager(
            localDeviceId = localDevice,
            devices = stack.ctx.devices,
            transport = stack.webRtc,
            plaintext = Plaintext(ByteArray(0)),
            ingest = requesterGate,
            serveGate = requesterGate,
            routerConfig = configFlow,
            frameBytes = MutableStateFlow(32 * 1024),
            events = events,
        )
        val registry = PeerAvailabilityRegistry(clock, configFlow, FakePeerAvailabilityStore())
        val sessionOpener = ProactiveSessionOpener(stack.ctx, registry)
        val processor = FileRequestProcessor(
            ctx = stack.ctx,
            attempts = attempts,
            requests = requesterGate,
            outboxProcessor = stack.outboxProcessor,
            peerPolicy = DefaultSyncPeerPolicy(stack.ctx),
            maxIdlePoll = MutableStateFlow(50.milliseconds),
            events = events,
            onNoLink = {},
            sessionOpener = sessionOpener,
            fetchManager = requesterManager,
        )
        val holderTransports = holderDevices.associateWith { RecordingWebRtcTransport() }
        val holderManagers = holderDevices.associateWith { device ->
            FileFetchManager(
                localDeviceId = device,
                devices = MapDevices(mapOf(localDevice to holderAccount)),
                transport = holderTransports.getValue(device),
                plaintext = Plaintext(dataBytes),
                ingest = FakeFileSource(canServe = holderCanServe(device)),
                serveGate = FakeFileSource(canServe = holderCanServe(device)),
                routerConfig = configFlow,
                frameBytes = MutableStateFlow(32 * 1024),
                events = holderEvents,
            )
        }
        val jobs = mutableListOf<Job>()
        jobs += requesterManager.runIn(scope)
        holderManagers.values.forEach { jobs += it.runIn(scope) }
        jobs += processor.runIn(scope)
        // File-frame pump between requester and holder transports, routed by target.
        jobs += scope.launch {
            val seenRequester = mutableSetOf<Triple<PeerId, String, ByteArray>>()
            val seenHolder = mutableMapOf<PeerId, MutableSet<Triple<PeerId, String, ByteArray>>>()
            val seenRequesterClosed = mutableSetOf<Pair<PeerId, String>>()
            val seenHolderClosed = mutableMapOf<PeerId, MutableSet<Pair<PeerId, String>>>()
            while (true) {
                delay(10)
                for (sent in stack.webRtc.sendFileFrameCalls.toList()) {
                    if (seenRequester.add(sent)) {
                        val (target, label, payload) = sent
                        val peerTransport = holderTransports[target] ?: continue
                        val frame = WebRtcDataFrame(localDevice, target, WebRtcDataType.FILE_DATA, payload, label)
                        while (!peerTransport.tryEmitFileFrame(frame)) delay(5)
                    }
                }
                for ((device, transport) in holderTransports) {
                    val seen = seenHolder.getOrPut(device) { mutableSetOf() }
                    for (sent in transport.sendFileFrameCalls.toList()) {
                        if (seen.add(sent)) {
                            val (_, label, payload) = sent
                            val frame = WebRtcDataFrame(device, localDevice, WebRtcDataType.FILE_DATA, payload, label)
                            while (!stack.webRtc.tryEmitFileFrame(frame)) delay(5)
                        }
                    }
                    val seenClosed = seenHolderClosed.getOrPut(device) { mutableSetOf() }
                    for (closed in transport.closeFileChannelCalls.toList()) {
                        if (seenClosed.add(closed)) {
                            stack.webRtc.tryEmitFileChannelEvent(
                                WebRtcFileChannelEvent.Removed(closed.first, closed.second)
                            )
                        }
                    }
                }
                for (closed in stack.webRtc.closeFileChannelCalls.toList()) {
                    if (seenRequesterClosed.add(closed)) {
                        holderTransports[closed.first]?.tryEmitFileChannelEvent(
                            WebRtcFileChannelEvent.Removed(localDevice, closed.second)
                        )
                    }
                }
            }
        }
        if (simulateSession) {
            // Sessions open on demand: report CONNECTED as soon as the loop asks.
            jobs += scope.launch {
                val simulated = mutableSetOf<PeerId>()
                while (true) {
                    delay(10)
                    for (peer in stack.webRtc.openSessionCalls.toList()) {
                        if (simulated.add(peer)) {
                            stack.webRtc.setSessionState(WebRtcSessionState(peer, SessionPhase.CONNECTED))
                            stack.webRtc.simulateEnvelopeChannelOpen(peer)
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
                    delay(50)
                    clock.advanceBy(100.milliseconds)
                }
            }
        }
        return Fixture(
            scope,
            processor,
            attempts,
            events,
            holderEvents,
            stack.webRtc,
            stack.tor,
            holderTransports,
            requesterGate,
            dataBytes,
            jobs
        )
    }

    @Test
    fun satisfied_endToEnd_nothingLargeOverTor() = runBlocking {
        val f = fixture()
        try {
            f.processor.start(fileId, now)
            withTimeout(20.seconds) {
                while (f.attempts.findById(fileId) != null) delay(50)
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
                while (f.attempts.findById(fileId) != null) delay(50)
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
                while (seen.none { it is FileEvent.NoDirectConnection }) delay(50)
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
                while (f.attempts.findById(fileId) != null) delay(50)
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
            delay(500)
            // Core owns small files: a sealed REQUEST went through the outbox.
            assertTrue(f.requesterWebRtc.openSessionCalls.isEmpty(), "large path never opens sessions for small files")
        } finally {
            f.stop()
        }
    }
}
