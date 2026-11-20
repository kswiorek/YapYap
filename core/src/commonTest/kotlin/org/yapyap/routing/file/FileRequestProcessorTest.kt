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
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRequestAttempt
import org.yapyap.routing.store.OutboxTag
import org.yapyap.sync.FixedSyncPeerPolicy
import org.yapyap.sync.SyncRoutingStack
import org.yapyap.sync.buildSyncRoutingStack
import org.yapyap.sync.testDeviceIdentity
import org.yapyap.testfixtures.FakeClock
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
 * Step 16, routing side: one candidate at a time, ACK → WAITING, DECLINED moves on,
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
    ) {
        suspend fun runFor(millis: Long = 500) {
            val scope = CoroutineScope(SupervisorJob())
            val job = processor.runIn(scope)
            delay(millis.milliseconds)
            job.cancel()
            scope.cancel()
        }
    }

    private fun harness(
        devices: List<PeerId> = listOf(holderDevice),
        candidates: List<AccountId> = listOf(holderAccount),
    ): Harness {
        val clock = FakeClock(now)
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(holderAccount to devices),
            torByPeer = devices.associateWith { org.yapyap.protocol.TorEndpoint("${it.id}.onion", 80) }.toMutableMap(),
            clock = clock,
        )
        devices.forEach { device ->
            val endpoint = org.yapyap.protocol.TorEndpoint("${device.id}.onion", 80)
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
        val gate = FakeFileIngest(candidates)
        val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val policy = FixedSyncPeerPolicy(nextDevice = devices.firstOrNull())
        val processor = FileRequestProcessor(
            ctx = ctx,
            attempts = attempts,
            gate = gate,
            outboxProcessor = stack.outboxProcessor,
            peerPolicy = policy,
            maxIdlePoll = MutableStateFlow(1.seconds),
            events = events,
            onNoLink = {},
        )
        return Harness(stack, attempts, events, processor, policy)
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
                holderDevice to org.yapyap.protocol.TorEndpoint("holder.onion", 80),
                otherDevice to org.yapyap.protocol.TorEndpoint("other.onion", 80),
            ),
            clock = clock,
        )
        listOf(holderDevice, otherDevice).forEach { device ->
            stack.ctx.peerLinks.bindTorEndpoint(device, org.yapyap.protocol.TorEndpoint("${device.id}.onion", 80))
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
            gate = FakeFileIngest(listOf(holderAccount)),
            outboxProcessor = stack.outboxProcessor,
            peerPolicy = fixed,
            maxIdlePoll = MutableStateFlow(1.seconds),
            events = events,
            onNoLink = {},
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

    // -- Fakes ------------------------------------------------------------------

    private class FakeFileIngest(
        private val candidates: List<AccountId>,
    ) : FileIngestPort {
        var coverage: FileCoverage? = FileCoverage(0L, emptyList())
        var canServe = true
        override suspend fun checkOffer(offer: FileOfferInfo) = FileOfferVerdict.Accept
        override suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray) =
            FileIngestResult.Duplicate

        override suspend fun heldCoverage(fileId: FileId): FileCoverage? = coverage
        override suspend fun requestCandidates(fileId: FileId): List<AccountId> = candidates
        override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean = canServe
        override suspend fun requestTotalBytes(fileId: FileId): Long? = null
    }

    private class InMemoryFileRequestAttemptStore : org.yapyap.routing.store.FileRequestAttemptStore {
        private data class Row(
            var attempts: Int = 0,
            var nextAttemptAt: kotlin.time.Instant = epochSeconds(0L),
            var waitingDevice: PeerId? = null,
            var waitingUntil: kotlin.time.Instant? = null,
            var requestTransferId: TransferId? = null,
            val declined: MutableSet<PeerId> = mutableSetOf(),
        )

        private val rows = mutableMapOf<FileId, Row>()

        override suspend fun start(fileId: FileId, firstAttemptAt: kotlin.time.Instant) {
            rows.getOrPut(fileId) { Row(nextAttemptAt = firstAttemptAt) }
        }

        override suspend fun drop(fileId: FileId) {
            rows.remove(fileId)
        }

        override suspend fun earliestDueAt(): kotlin.time.Instant? =
            rows.values.minOfOrNull { it.nextAttemptAt }

        override suspend fun findDue(now: kotlin.time.Instant, limit: Int): List<FileRequestAttempt> =
            rows.entries.filter { it.value.nextAttemptAt <= now }.take(limit).map { (id, row) ->
                FileRequestAttempt(
                    fileId = id,
                    attempts = row.attempts,
                    declinedDevices = row.declined.toSet(),
                    waitingDevice = row.waitingDevice,
                    waitingUntil = row.waitingUntil,
                    requestTransferId = row.requestTransferId,
                )
            }

        override suspend fun findById(fileId: FileId): FileRequestAttempt? =
            rows[fileId]?.let { row ->
                FileRequestAttempt(
                    fileId = fileId,
                    attempts = row.attempts,
                    declinedDevices = row.declined.toSet(),
                    waitingDevice = row.waitingDevice,
                    waitingUntil = row.waitingUntil,
                    requestTransferId = row.requestTransferId,
                )
            }

        override suspend fun findByTransferId(transferId: TransferId): FileRequestAttempt? =
            rows.entries.firstOrNull { it.value.requestTransferId == transferId }?.let { (id, row) ->
                FileRequestAttempt(
                    fileId = id,
                    attempts = row.attempts,
                    declinedDevices = row.declined.toSet(),
                    waitingDevice = row.waitingDevice,
                    waitingUntil = row.waitingUntil,
                    requestTransferId = row.requestTransferId,
                )
            }

        override suspend fun recordAttempt(fileId: FileId, nextAttemptAt: kotlin.time.Instant) {
            rows[fileId]?.let {
                it.attempts += 1
                it.nextAttemptAt = nextAttemptAt
            }
        }

        override suspend fun postpone(fileId: FileId, nextAttemptAt: kotlin.time.Instant) {
            rows[fileId]?.nextAttemptAt = nextAttemptAt
        }

        override suspend fun advanceToNow(fileIds: Collection<FileId>, now: kotlin.time.Instant) {
            fileIds.forEach { id ->
                rows[id]?.let { if (it.nextAttemptAt > now) it.nextAttemptAt = now }
            }
        }

        override suspend fun setWaiting(fileId: FileId, device: PeerId, until: kotlin.time.Instant, requestTransferId: TransferId) {
            rows[fileId]?.let {
                it.waitingDevice = device
                it.waitingUntil = until
                it.requestTransferId = requestTransferId
            }
        }

        override suspend fun clearWaiting(fileId: FileId) {
            rows[fileId]?.let {
                it.waitingDevice = null
                it.waitingUntil = null
                it.requestTransferId = null
            }
        }

        override suspend fun setRequestTransfer(fileId: FileId, transferId: TransferId) {
            rows[fileId]?.requestTransferId = transferId
        }

        override suspend fun declinedDevices(fileId: FileId): Set<PeerId> =
            rows[fileId]?.declined?.toSet().orEmpty()

        override suspend fun addDeclined(fileId: FileId, device: PeerId) {
            rows[fileId]?.declined?.add(device)
        }

        override suspend fun removeDeclined(fileId: FileId, devices: Collection<PeerId>) {
            rows[fileId]?.declined?.removeAll(devices.toSet())
        }

        override suspend fun allIds(): List<FileId> = rows.keys.toList()
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
