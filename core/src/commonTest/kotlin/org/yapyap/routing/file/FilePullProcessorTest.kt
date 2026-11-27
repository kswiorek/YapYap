package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.file.large.LargeFileService
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.link.PeerLinks
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.InMemoryFilePullAttemptStore
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RefusingFilePorts
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.routing.store.FilePullClass
import org.yapyap.routing.store.OutboxTag
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.FakeFileSource
import org.yapyap.testfixtures.NoPlaintext
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Routing side of file requests: one candidate at a time, ACK → WAITING, DECLINED moves on,
 * WAITING timeout moves on without declining, unknown outcomes are ignored.
 */
class FilePullProcessorTest {

    private val localDevice = PeerId("filereq-local-device")
    private val holderDevice = PeerId("filereq-holder-device")
    private val otherDevice = PeerId("filereq-other-device")
    private val holderAccount = AccountId("filereq-holder-account")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val now = epochSeconds(10_000L)

    private class FixedPeerPolicy(var nextDevice: PeerId? = null) : SyncPeerPolicy {
        override fun pickNextDevice(
            candidates: List<PeerId>,
            declined: Set<PeerId>,
            unanswered: Set<PeerId>,
        ): PeerId? = nextDevice
    }

    private class Harness(
        val ctx: RoutingContext,
        val outbox: TrackingPacketOutbox,
        val outboxProcessor: OutboxProcessor,
        val attempts: InMemoryFilePullAttemptStore,
        val events: MutableSharedFlow<FileEvent>,
        val processor: FilePullProcessor,
        val policy: FixedPeerPolicy,
        val gate: FakeFileSource,
    ) {
        suspend fun runFor(millis: Long = 500) {
            val scope = CoroutineScope(SupervisorJob())
            val job = processor.runIn(scope)
            delay(millis.milliseconds)
            job.cancel()
            scope.cancel()
        }
    }

    // The large path is wired but idle here: every request in these tests is small.
    private fun idleLinkKeeper(
        ctx: RoutingContext,
        clock: FakeClock,
        config: RouterConfig,
        webRtc: Boolean = false,
    ) = LinkKeeper(
        name = if (webRtc) "test-webrtc" else "test-tor",
        sessions = if (webRtc) ctx.webRtcTransport else ctx.peerLinks.tor,
        targets = { emptyList() },
        ready = flowOf(false),
        config = MutableStateFlow(
            if (webRtc) config.webrtcLinkKeeperConfig else config.torLinkKeeperConfig
        ),
        clock = clock,
    )

    private fun idleLargeFiles(
        ctx: RoutingContext,
        clock: FakeClock,
        config: RouterConfig,
        events: MutableSharedFlow<FileEvent>,
    ) =
        LargeFileService(
            devices = ctx.devices,
            transport = ctx.webRtcTransport,
            plaintext = NoPlaintext,
            ingest = RefusingFilePorts(),
            requests = RefusingFilePorts(),
            routerConfig = MutableStateFlow(config),
            fileLimits = MutableStateFlow(FileLimits(220 * 1024, 220 * 1024, maxSealBytes = 64 * 1024)),
            events = events,
            clock = clock,
        )

    private fun harness(
        devices: List<PeerId> = listOf(holderDevice),
        candidates: List<AccountId> = listOf(holderAccount),
    ): Harness {
        val clock = FakeClock(now)
        val routerConfig = RouterConfig()
        val configFlow = MutableStateFlow(routerConfig)
        val local = DeviceIdentity(
            localDevice,
            SigningPublicKey(byteArrayOf(1)),
            EncryptionPublicKey(byteArrayOf(2)),
        )
        val identity = FakeIdentityResolverForRouter(
            localDevice = local,
            peersByAccount = mapOf(holderAccount to devices),
            torByPeer = devices.associateWith { TorEndpoint("${it.id}.onion", 80) }.toMutableMap(),
        )
        val tor = RecordingTorTransport()
        val webRtc = RecordingWebRtcTransport()
        val ctx = RoutingContext(
            localIdentity = identity,
            localDeviceId = localDevice,
            devices = identity,
            torTransport = tor,
            webRtcTransport = webRtc,
            peerLinks = PeerLinks(identity, webRtc, tor),
            transportLimits = MutableStateFlow(testTransportLimits()),
            fileLimits = MutableStateFlow(FileLimits(220 * 1024, 220 * 1024, maxSealBytes = 64 * 1024)),
            envelopeProtections = org.yapyap.routing.router.PassthroughFakeEnvelopeProtectionService(),
            clock = clock,
            routerConfig = configFlow,
        )
        devices.forEach { device ->
            val endpoint = TorEndpoint("${device.id}.onion", 80)
            ctx.peerLinks.bindTorEndpoint(device, endpoint)
        }
        val outbox = TrackingPacketOutbox()
        val outboxProcessor = outboxProcessorUnderTest(
            tor = tor,
            webRtc = webRtc,
            identity = identity,
            outbox = outbox,
            clock = clock,
            routerConfig = routerConfig,
        )
        val attempts = InMemoryFilePullAttemptStore()
        val gate = FakeFileSource(candidates = candidates)
        val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val policy = FixedPeerPolicy(nextDevice = devices.firstOrNull())
        val processor = FilePullProcessor(
            ctx = ctx,
            packets = FilePackets(ctx),
            pullAttempts = attempts,
            requests = gate,
            outboxProcessor = outboxProcessor,
            peerPolicy = policy,
            maxIdlePoll = MutableStateFlow(1.seconds),
            events = events,
            linkKeeper = idleLinkKeeper(ctx, clock, routerConfig),
            webRtcLinks = idleLinkKeeper(ctx, clock, routerConfig, webRtc = true),
            largeFiles = idleLargeFiles(ctx, clock, routerConfig, events),
        )
        return Harness(ctx, outbox, outboxProcessor, attempts, events, processor, policy, gate)
    }

    private suspend fun Harness.request(): Uuid {
        processor.start(fileId, immediate = true)
        runFor()
        val row = attempts.findById(fileId)
        return assertNotNull(row?.attemptId, "attempt must record its REQUEST attempt")
    }

    private fun Harness.queuedRequest(): Pair<BinaryEnvelope, OutboxTag> {
        val queued = outbox.enqueued.single()
        val tag = outbox.tagOf(queued.packetId)
        assertNotNull(tag)
        assertEquals(OutboxTag.Kind.FILE_REQUEST, tag.kind)
        assertEquals(holderDevice, queued.target)
        assertEquals(PacketType.FILE, queued.packetType)
        assertTrue(queued.dispositionRequested)
        return queued to tag
    }

    @Test
    fun dueRequest_enqueuesSealedTaggedRequest() = kotlinx.coroutines.runBlocking {
        val h = harness()
        val attemptId = h.request()
        val (envelope, tag) = h.queuedRequest()
        assertEquals(attemptId, tag.id)
        val request = FileRequest.decode(envelope.content)
        assertEquals(fileId, request.fileId)
        assertEquals(0L, request.contiguousBytes)
    }

    @Test
    fun ack_movesToWaitingWithoutNewAttempt() = kotlinx.coroutines.runBlocking {
        val h = harness()
        h.request()
        val (_, tag) = h.queuedRequest()
        h.processor.onAttemptOutcome(OutboxOutcome.Acked(tag, holderDevice, Uuid.random()))
        val row = assertNotNull(h.attempts.findById(fileId))
        assertEquals(holderDevice, row.waitingDevice)
        h.runFor()
        assertEquals(1, h.outbox.enqueued.size, "no second REQUEST while WAITING")
    }

    @Test
    fun offerArrival_satisfiesDropsRowAndEmits() = kotlinx.coroutines.runBlocking {
        val h = harness()
        val seen = mutableListOf<FileEvent>()
        val collect = launch { h.events.collect { seen += it } }
        h.request()
        h.processor.onOfferReceived(fileId)
        assertNull(h.attempts.findById(fileId))
        collect.cancel()
    }

    @Test
    fun declined_movesToNextCandidate() = kotlinx.coroutines.runBlocking {
        val h = harness(devices = listOf(holderDevice, otherDevice))
        h.request()
        val (_, tag) = h.queuedRequest()
        h.processor.onAttemptOutcome(
            OutboxOutcome.Nacked(tag, holderDevice, Uuid.random(), PacketNackReason.DECLINED)
        )
        assertTrue(holderDevice in h.attempts.declinedDevices(fileId))
    }

    @Test
    fun allDeclined_reportsUnavailableAndDropsRow() = kotlinx.coroutines.runBlocking {
        val h = harness()
        val seen = mutableListOf<FileEvent>()
        val collect = launch { h.events.collect { seen += it } }
        h.request()
        val (packet, tag) = h.queuedRequest()
        // Production removes the entry on disposition before the outcome reaches us.
        h.outboxProcessor.onDisposition(packet.packetId, PacketNackReason.DECLINED)
        h.processor.onAttemptOutcome(
            OutboxOutcome.Nacked(tag, holderDevice, packet.packetId, PacketNackReason.DECLINED)
        )
        // Exhaustion is evaluated on the next due cycle (the loop is always running in
        // production); run it again so the last candidate's decline reports UNAVAILABLE.
        // The fixed test policy always answers; null it to simulate the real policy
        // finding no sendable candidate once everyone declined.
        h.policy.nextDevice = null
        h.runFor()
        assertNull(h.attempts.findById(fileId))
        kotlinx.coroutines.yield()
        collect.cancel()
        assertTrue(seen.any { it is FileEvent.RequestUnavailable && it.fileId == fileId })
    }

    @Test
    fun waitingTimeout_asksNextCandidateWithoutDeclining() = kotlinx.coroutines.runBlocking {
        val h = harness(devices = listOf(holderDevice, otherDevice))
        val clock = FakeClock(now)
        h.processor.start(fileId, immediate = true)
        CoroutineScope(SupervisorJob()).let { scope ->
            val job = h.processor.runIn(scope)
            delay(300.milliseconds)
            job.cancel()
            scope.cancel()
        }
        val (_, firstTag) = h.outbox.enqueued.single().let { it to h.outbox.tagOf(it.packetId)!! }
        h.processor.onAttemptOutcome(OutboxOutcome.Acked(firstTag, holderDevice, Uuid.random()))
        // The holder never sends its OFFER: advance past the wait timeout.
        (h.ctx.clock as FakeClock).advanceBy(91.seconds)
        h.policy.nextDevice = otherDevice
        h.runFor()
        assertEquals(2, h.outbox.enqueued.size, "timed-out WAITING asks the next candidate")
        assertTrue(holderDevice !in h.attempts.declinedDevices(fileId), "timeout is not a refusal")
    }

    @Test
    fun unknownOutcome_isIgnored() = kotlinx.coroutines.runBlocking {
        val h = harness()
        h.processor.onAttemptOutcome(
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE_REQUEST, Uuid.random()), holderDevice, Uuid.random())
        )
        assertNull(h.attempts.findById(fileId))
    }

    @Test
    fun smallRow_winsFirstPassAgainstTenLarge() = kotlinx.coroutines.runBlocking {
        val h = harness()
        val largeIds = (0 until 10).map { hexFileId(it) }
        val smallId = hexFileId(100)
        h.gate.totalBytesByFile = (largeIds.associateWith { 11L * 1024L * 1024L } + (smallId to 1_000L))
        for (id in largeIds) h.processor.start(id, immediate = true)
        h.processor.start(smallId, immediate = true)

        h.runFor()

        val queued = h.outbox.enqueued
        assertEquals(1, queued.size, "only the SMALL REQUEST is enqueued in the first pass")
        assertEquals(smallId, FileRequest.decode(queued.single().content).fileId)
        assertTrue(
            largeIds.all { h.attempts.findById(it) != null },
            "LARGE rows stay pending on the fetch path",
        )
    }

    @Test
    fun start_automaticSmallFile_waitsThePushWindow() = kotlinx.coroutines.runBlocking {
        val h = harness()
        h.gate.totalBytesByFile = mapOf(fileId to 1_000L)

        h.processor.start(fileId, immediate = false)

        val config = h.ctx.routerConfig.value
        assertEquals(FilePullClass.SMALL, h.attempts.findById(fileId)?.pullClass)
        assertEquals(now + config.filePullDelay, h.attempts.nextAttemptAt(fileId))
    }

    @Test
    fun start_automaticLargeFile_startsAtOnce() = kotlinx.coroutines.runBlocking {
        val h = harness()
        val config = h.ctx.routerConfig.value
        h.gate.totalBytesByFile = mapOf(fileId to config.smallFileMaxBytes + 1)

        h.processor.start(fileId, immediate = false)

        assertEquals(FilePullClass.LARGE, h.attempts.findById(fileId)?.pullClass)
        assertEquals(now, h.attempts.nextAttemptAt(fileId), "large files never wait the small path's push window")
    }

    @Test
    fun start_explicitSmallFile_startsAtOnce() = kotlinx.coroutines.runBlocking {
        val h = harness()
        h.gate.totalBytesByFile = mapOf(fileId to 1_000L)

        h.processor.start(fileId, immediate = true)

        assertEquals(FilePullClass.SMALL, h.attempts.findById(fileId)?.pullClass)
        assertEquals(now, h.attempts.nextAttemptAt(fileId))
    }

    @Test
    fun start_explicit_rearmsAnExistingFutureRow() = kotlinx.coroutines.runBlocking {
        val h = harness()
        h.gate.totalBytesByFile = mapOf(fileId to 1_000L)
        h.attempts.start(fileId, FilePullClass.SMALL, now + 5.seconds)

        h.processor.start(fileId, immediate = true)

        assertEquals(now, h.attempts.nextAttemptAt(fileId), "explicit intent moves the existing schedule to now")
    }

    @Test
    fun start_automatic_keepsAnExistingSchedule() = kotlinx.coroutines.runBlocking {
        val h = harness()
        h.gate.totalBytesByFile = mapOf(fileId to 1_000L)
        h.attempts.start(fileId, FilePullClass.SMALL, now + 5.seconds)

        h.processor.start(fileId, immediate = false)

        assertEquals(
            now + 5.seconds,
            h.attempts.nextAttemptAt(fileId),
            "an automatic re-arm never moves a known row's schedule",
        )
    }

    // -- Fakes ------------------------------------------------------------------

    private companion object {
        /** 64 lowercase hex chars, like a real content hash. */
        fun hexFileId(n: Int): FileId = FileId(n.toString(16).padStart(64, '0'))
    }
}
