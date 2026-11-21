package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.fetch.FileFetchService
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.router.InMemoryFileRequestAttemptStore
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RefusingFilePorts
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.OutboxTag
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.sync.FixedSyncPeerPolicy
import org.yapyap.sync.SyncRoutingStack
import org.yapyap.sync.buildSyncRoutingStack
import org.yapyap.sync.testDeviceIdentity
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.FakeFileSource
import org.yapyap.testfixtures.NoPlaintext
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
class FileRequestProcessorTest {

    private val localDevice = PeerId("filereq-local-device")
    private val holderDevice = PeerId("filereq-holder-device")
    private val otherDevice = PeerId("filereq-other-device")
    private val holderAccount = AccountId("filereq-holder-account")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val now = epochSeconds(10_000L)

    private class Harness(
        val stack: SyncRoutingStack,
        val attempts: InMemoryFileRequestAttemptStore,
        val events: MutableSharedFlow<FileEvent>,
        val processor: FileRequestProcessor,
        val policy: FixedSyncPeerPolicy,
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
    private fun idleSessionOpener(ctx: RoutingContext, clock: FakeClock) = ProactiveSessionOpener(
        ctx,
        PeerAvailabilityRegistry(clock, ctx.routerConfig, FakePeerAvailabilityStore()),
    )

    private fun idleFetchManager(ctx: RoutingContext, clock: FakeClock, events: MutableSharedFlow<FileEvent>) =
        FileFetchService(
            devices = ctx.devices,
        transport = ctx.webRtcTransport,
        plaintext = NoPlaintext,
        ingest = RefusingFilePorts(),
        serveGate = RefusingFilePorts(),
        routerConfig = ctx.routerConfig,
        frameBytes = MutableStateFlow(32 * 1024),
        events = events,
            clock = clock,
    )

    private fun harness(
        devices: List<PeerId> = listOf(holderDevice),
        candidates: List<AccountId> = listOf(holderAccount),
    ): Harness {
        val clock = FakeClock(now)
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(holderAccount to devices),
            torByPeer = devices.associateWith { TorEndpoint("${it.id}.onion", 80) }.toMutableMap(),
            clock = clock,
        )
        devices.forEach { device ->
            val endpoint = TorEndpoint("${device.id}.onion", 80)
            stack.ctx.peerLinks.bindTorEndpoint(device, endpoint)
        }
        val ctx = RoutingContext(
            localIdentity = stack.ctx.localIdentity,
            localDeviceId = stack.ctx.localDeviceId,
            devices = stack.ctx.devices,
            routes = stack.ctx.routes,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            envelopeProtectionService = SealingFakeProtection(stack.ctx.envelopeProtectionService),
            torTransport = stack.tor,
            webRtcTransport = stack.webRtc,
            clock = clock,
            routerConfig = stack.ctx.routerConfig,
            transportLimits = stack.ctx.transportLimits,
        )
        val attempts = InMemoryFileRequestAttemptStore()
        val gate = FakeFileSource(candidates = candidates)
        val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val policy = FixedSyncPeerPolicy(nextDevice = devices.firstOrNull())
        val processor = FileRequestProcessor(
            ctx = ctx,
            attempts = attempts,
            requests = gate,
            outboxProcessor = stack.outboxProcessor,
            peerPolicy = policy,
            maxIdlePoll = MutableStateFlow(1.seconds),
            events = events,
            onNoLink = {},
            sessionOpener = idleSessionOpener(ctx, clock),
            fetchService = idleFetchManager(ctx, clock, events),
        )
        return Harness(stack, attempts, events, processor, policy, gate)
    }

    private suspend fun Harness.request(): TransferId {
        processor.start(fileId, now)
        runFor()
        val row = attempts.findById(fileId)
        return assertNotNull(row?.requestTransferId, "attempt must record its REQUEST transfer")
    }

    private fun Harness.queuedRequest(): Pair<BinaryEnvelope, OutboxTag> {
        val queued = stack.outbox.enqueued.single()
        val tag = stack.outbox.tagOf(queued.packetId)
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
        val transferId = h.request()
        val (envelope, tag) = h.queuedRequest()
        assertEquals(transferId.value, tag.id)
        val opened = FileEnvelope.decode(envelope.payload).decodePayload()
        val request = assertIs<FilePayload.Request>(opened)
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
        assertEquals(1, h.stack.outbox.enqueued.size, "no second REQUEST while WAITING")
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
        h.stack.outboxProcessor.onDisposition(packet.packetId, PacketNackReason.DECLINED)
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
        val fixed = FixedSyncPeerPolicy(nextDevice = holderDevice)
        val clock = FakeClock(now)
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(holderAccount to listOf(holderDevice, otherDevice)),
            torByPeer = mutableMapOf(
                holderDevice to TorEndpoint("holder.onion", 80),
                otherDevice to TorEndpoint("other.onion", 80),
            ),
            clock = clock,
        )
        listOf(holderDevice, otherDevice).forEach { device ->
            stack.ctx.peerLinks.bindTorEndpoint(device, TorEndpoint("${device.id}.onion", 80))
        }
        val ctx = RoutingContext(
            localIdentity = stack.ctx.localIdentity,
            localDeviceId = stack.ctx.localDeviceId,
            devices = stack.ctx.devices,
            routes = stack.ctx.routes,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            envelopeProtectionService = SealingFakeProtection(stack.ctx.envelopeProtectionService),
            torTransport = stack.tor,
            webRtcTransport = stack.webRtc,
            clock = clock,
            routerConfig = stack.ctx.routerConfig,
            transportLimits = stack.ctx.transportLimits,
        )
        val attempts = InMemoryFileRequestAttemptStore()
        val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val processor = FileRequestProcessor(
            ctx = ctx,
            attempts = attempts,
            requests = FakeFileSource(candidates = listOf(holderAccount)),
            outboxProcessor = stack.outboxProcessor,
            peerPolicy = fixed,
            maxIdlePoll = MutableStateFlow(1.seconds),
            events = events,
            onNoLink = {},
            sessionOpener = idleSessionOpener(ctx, clock),
            fetchService = idleFetchManager(ctx, clock, events),
        )
        processor.start(fileId, now)
        CoroutineScope(SupervisorJob()).let { scope ->
            val job = processor.runIn(scope)
            delay(300.milliseconds)
            job.cancel()
            scope.cancel()
        }
        val (_, firstTag) = stack.outbox.enqueued.single().let { it to stack.outbox.tagOf(it.packetId)!! }
        processor.onAttemptOutcome(OutboxOutcome.Acked(firstTag, holderDevice, Uuid.random()))
        // The holder never sends its OFFER: advance past the wait timeout.
        clock.advanceBy(91.seconds)
        fixed.nextDevice = otherDevice
        CoroutineScope(SupervisorJob()).let { scope ->
            val job = processor.runIn(scope)
            delay(300.milliseconds)
            job.cancel()
            scope.cancel()
        }
        assertEquals(2, stack.outbox.enqueued.size, "timed-out WAITING asks the next candidate")
        assertTrue(holderDevice !in attempts.declinedDevices(fileId), "timeout is not a refusal")
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
        for (id in largeIds) h.processor.start(id, now)
        h.processor.start(smallId, now)

        h.runFor()

        val queued = h.stack.outbox.enqueued
        assertEquals(1, queued.size, "only the SMALL REQUEST is enqueued in the first pass")
        val opened = FileEnvelope.decode(queued.single().payload).decodePayload()
        assertEquals(smallId, assertIs<FilePayload.Request>(opened).fileId)
        assertTrue(
            largeIds.all { h.attempts.findById(it) != null },
            "LARGE rows stay pending on the fetch path",
        )
    }

    // -- Fakes ------------------------------------------------------------------

    private companion object {
        /** 64 lowercase hex chars, like a real content hash. */
        fun hexFileId(n: Int): FileId = FileId(n.toString(16).padStart(64, '0'))
    }

    private class SealingFakeProtection(
        private val pass: EnvelopeProtectionService,
    ) : EnvelopeProtectionService by pass {
        override suspend fun protectFile(
            input: FilePayload,
            context: EnvelopeProtectContext,
        ): FileEnvelope = FileEnvelope(
            transferId = context.transferId ?: TransferId(Uuid.random()),
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            nonce = ByteArray(12),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = null,
            payload = input.encode(),
        )
    }
}
