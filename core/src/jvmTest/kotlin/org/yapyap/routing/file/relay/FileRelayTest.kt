package org.yapyap.routing.file.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.MIN_FILE_CHUNK_SIZE_BYTES
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.FileSeal
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.TransferShape
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.file.FilePackets
import org.yapyap.routing.file.ScriptedFilePorts
import org.yapyap.routing.file.ScriptedPlaintext
import org.yapyap.routing.file.inbound.FileInboundHandler
import org.yapyap.routing.file.inbound.FileServeHandler
import org.yapyap.routing.file.inbound.TargetSide
import org.yapyap.routing.file.large.LargeFileService
import org.yapyap.routing.file.seal.SealRegistry
import org.yapyap.routing.file.transfer.TransferEngine
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.routing.router.passthroughProtections
import org.yapyap.routing.router.testRoutingContext
import org.yapyap.routing.store.DefaultFileIncomingStore
import org.yapyap.routing.store.DefaultFilePullAttemptStore
import org.yapyap.routing.store.DefaultFileRelayStore
import org.yapyap.routing.store.DefaultFileSealStore
import org.yapyap.routing.store.DefaultFileTransferStore
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.OutboxTag
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The relay door and the forward driver (envelope-redesign §5.4).
 * The relay is dumb to content: admitted at the door, ciphertext stored
 * verbatim, forwarded only when whole.
 */
class FileRelayTest {

    private val now = epochSeconds(10_000L)
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val chunkSize = MIN_FILE_CHUNK_SIZE_BYTES
    private val totalBytes = 3L * chunkSize

    private val authorId = PeerId("relaytestauthoraaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val relayId = PeerId("relaytestrelaynodebbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val targetId = PeerId("relaytesttargetcccccccccccccccccccccccccccccccccccccccccccc")
    private val strangerId = PeerId("relayteststrangerdddddddddddddddddddddddddddddddddddddddddd")

    private inner class Harness(
        val handler: FileInboundHandler,
        val relay: DefaultFileRelayStore,
        val transfers: DefaultFileTransferStore,
        val forwards: RelayForwards,
        val engine: TransferEngine,
        val outbox: TrackingPacketOutbox,
        val ctx: RoutingContext,
        val clock: FakeClock,
        val authorId: PeerId = this@FileRelayTest.authorId,
        val relayId: PeerId = this@FileRelayTest.relayId,
        val targetId: PeerId = this@FileRelayTest.targetId,
    ) {
        val sent: List<Pair<BinaryEnvelope, OutboxTag?>>
            get() = outbox.enqueued
                .filter { outbox.contains(it.packetId) }
                .map { env -> env to outbox.tagOf(env.packetId) }
    }

    private suspend fun Harness.awaitForwardGone(relayTransferId: TransferId) {
        withTimeout(5_000.milliseconds) { while (relay.find(relayTransferId) != null) delay(10.milliseconds) }
    }

    private suspend fun Harness.awaitSentSize(size: Int) {
        withTimeout(5_000.milliseconds) { while (sent.size < size) delay(10.milliseconds) }
    }

    private fun harness(
        pledgeBytes: Long = 100L * 1024 * 1024,
        bannedAccounts: Set<AccountId> = emptySet(),
    ): Harness {
        val clock = FakeClock(now)
        val config = RouterConfig(relayStoragePledgeBytes = pledgeBytes)
        val identity = FakeIdentityResolverForRouter(
            localDevice = DeviceIdentity(
                deviceId = relayId,
                signingKey = SigningPublicKey(byteArrayOf(1)),
                encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
            ),
            peersByAccount = mapOf(
                AccountId("author-acct") to listOf(authorId),
                AccountId("target-acct") to listOf(targetId),
            ),
            accountStatuses = bannedAccounts.associateWith { IdentityStatus.BANNED }.toMutableMap(),
        )
        val tor = RecordingTorTransport()
        val webRtc = RecordingWebRtcTransport()
        val ctx = testRoutingContext(
            identity = identity,
            tor = tor,
            webRtc = webRtc,
            clock = clock,
            routerConfig = config,
            protections = passthroughProtections(),
            fileChunkSize = chunkSize,
        )
        val db = openMemoryDatabase().database
        val relay = DefaultFileRelayStore(db, Dispatchers.Unconfined)
        val transfers = DefaultFileTransferStore(db, Dispatchers.Unconfined)
        val seals = DefaultFileSealStore(db, Dispatchers.Unconfined)
        val incoming = DefaultFileIncomingStore(db, Dispatchers.Unconfined)
        val attempts = DefaultFilePullAttemptStore(db, Dispatchers.Unconfined)
        val outbox = TrackingPacketOutbox()
        val processor = outboxProcessorUnderTest(
            tor = tor, webRtc = webRtc, identity = identity, outbox = outbox, clock = clock, routerConfig = config,
        )
        val plaintext = ScriptedPlaintext()
        val ports = ScriptedFilePorts()
        val events = MutableSharedFlow<org.yapyap.routing.file.port.FileEvent>(extraBufferCapacity = 64)
        val packets = FilePackets(ctx)
        val engine = TransferEngine(ctx, packets, transfers, seals, relay, plaintext, processor, outbox)
        val forwards = RelayForwards(ctx, relay, transfers, engine)
        val door = RelayDoor(ctx, packets, relay, forwards)
        // Target side for offers addressed to us (not exercised: our offers are for others).
        // Build a minimal target with a dummy request processor that never runs.
        val fetchService = LargeFileService(
            devices = ctx.devices,
            transport = ctx.webRtcTransport,
            plaintext = plaintext,
            ingest = ports,
            requests = ports,
            routerConfig = MutableStateFlow(config),
            fileLimits = ctx.fileLimits,
            events = events,
            clock = clock,
        )
        val idleKeeper = LinkKeeper(
            name = "idle", sessions = ctx.peerLinks.tor, targets = { emptyList() },
            ready = flowOf(false), config = MutableStateFlow(config.torLinkKeeperConfig), clock = clock,
        )
        val requestProcessor = org.yapyap.routing.file.FilePullProcessor(
            ctx = ctx, packets = packets, pullAttempts = attempts, requests = ports, outboxProcessor = processor,
            peerPolicy = org.yapyap.routing.policy.DefaultSyncPeerPolicy(ctx),
            maxIdlePoll = MutableStateFlow(1.seconds), events = events,
            linkKeeper = idleKeeper, webRtcLinks = idleKeeper, fetchService = fetchService,
        )
        val target = TargetSide(ctx, packets, incoming, ports, ports, requestProcessor, processor)
        val sealRegistry = SealRegistry(ctx, seals, transfers, engine, plaintext, NoRelaySelection, events)
        val serve = FileServeHandler(ctx, ports, sealRegistry)
        val handler = FileInboundHandler(ctx, target, door, engine, sealRegistry, serve)
        return Harness(handler, relay, transfers, forwards, engine, outbox, ctx, clock)
    }

    private fun seal(
        key: SealKey = SealKey(ByteArray(32) { it.toByte() }),
        total: Long = totalBytes,
    ) = FileSeal(
        fileId = fileId,
        totalBytes = total,
        blockSize = 1024 * 1024,
        blockHashes = listOf(ByteArray(32) { 1 }),
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        key = key,
    )

    private suspend fun Harness.depositOffer(
        transferId: TransferId = TransferId(Uuid.random()),
        total: Long = totalBytes,
        shape: TransferShape = TransferShape(chunkSize, 0, total),
        key: SealKey = SealKey(ByteArray(32) { it.toByte() }),
        origin: PeerId = authorId,
        finalTarget: PeerId = targetId,
        source: PeerId = origin,
    ): BinaryEnvelope {
        val sealBytes = ctx.envelopeProtections.file.seal(seal(key, total), finalTarget)
        return BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = source,
            target = relayId,
            routingHeader = FileHeader.Offer(transferId, origin, finalTarget, shape).encode(),
            content = sealBytes,
            signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
        )
    }

    private suspend fun Harness.depositChunk(
        transferId: TransferId,
        chunkIndex: Int,
        shape: TransferShape = TransferShape(chunkSize, 0, totalBytes),
        key: SealKey = SealKey(ByteArray(32) { it.toByte() }),
        plaintextBytes: Int = -1,
        source: PeerId = authorId,
    ): BinaryEnvelope {
        val plainLen = if (plaintextBytes < 0) shape.chunkLength(chunkIndex) else plaintextBytes
        val sealId = ctx.envelopeProtections.hop.contentId(
            ctx.envelopeProtections.file.seal(seal(key), targetId)
        )
        val ciphertext = ctx.envelopeProtections.file.encryptChunk(
            sealId, key, shape.chunkOffset(chunkIndex), ByteArray(plainLen) { chunkIndex.toByte() },
        )
        return BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = source,
            target = relayId,
            routingHeader = FileHeader.Chunk(transferId, chunkIndex).encode(),
            content = ciphertext,
            signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
        )
    }

    private suspend fun Harness.depositWhole(
        transferId: TransferId = TransferId(Uuid.random()),
    ): TransferId {
        assertIs<InboundHandleResult.Success>(handler.handle(depositOffer(transferId)))
        for (i in 0 until 3) {
            assertIs<InboundHandleResult.Success>(handler.handle(depositChunk(transferId, i)))
        }
        val status = relay.find(transferId)?.status
        assertEquals(FileRelayStatus.WHOLE, status, "expected WHOLE, got $status")
        return transferId
    }

    @Test
    fun concurrentForwardStarts_sendOneMarker() = runTest {
        val h = harness()
        h.engine.runIn(backgroundScope)
        h.forwards.runIn(backgroundScope)
        val transferId = h.depositWhole()
        // WHOLE triggers the forward at once; racing onWhole calls still make one.
        kotlinx.coroutines.coroutineScope {
            repeat(20) {
                launch { h.forwards.onWhole(transferId) }
            }
        }
        h.awaitSentSize(1)
        // One forward, one marker: exactly one outgoing transfer for the relay deposit.
        val forwards = h.transfers.byRelayTransfer(transferId)
        assertEquals(1, forwards.size, "one forward, one marker")
    }

    @Test
    fun bannedAccountOrigin_isDeclinedWithoutStoring() = runTest {
        val h = harness(bannedAccounts = setOf(AccountId("author-acct")))
        val transferId = TransferId(Uuid.random())
        val rejected = assertIs<InboundHandleResult.Rejected>(h.handler.handle(h.depositOffer(transferId)))
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertNull(h.relay.find(transferId), "a banned account's deposit stores nothing")
    }

    @Test
    fun unknownInnerSource_isDeclinedWithoutStoring() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val result = h.handler.handle(
            h.depositOffer(transferId, origin = strangerId, source = strangerId)
        )
        val rejected = assertIs<InboundHandleResult.Rejected>(result, "unknown inner source is declined")
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertNull(h.relay.find(transferId), "a declined admission stores nothing")
    }

    @Test
    fun chunkForUnknownTransfer_isDeclined() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val result = h.handler.handle(h.depositChunk(transferId, 0))
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, result.reason)
        assertTrue(h.relay.all().isEmpty())
    }

    @Test
    fun chunkOutOfRange_isDeclined() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        val result = h.handler.handle(h.depositChunk(transferId, 7, plaintextBytes = chunkSize))
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, result.reason)
        assertEquals(0, h.relay.chunkIndices(transferId).size, "out-of-range bytes never count toward wholeness")
    }

    @Test
    fun partialTransfer_isNeverForwarded() = runTest {
        val h = harness()
        h.engine.runIn(backgroundScope)
        h.forwards.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(transferId, 0)))
        assertEquals(FileRelayStatus.RESERVED, h.relay.find(transferId)?.status)
        delay(300.milliseconds)
        assertTrue(h.sent.isEmpty(), "an incomplete transfer is never pushed or offered")
    }

    @Test
    fun wholeTransfer_forwardsMarkerThenChunksVerbatim() = runTest {
        val h = harness()
        h.engine.runIn(backgroundScope)
        h.forwards.runIn(backgroundScope)
        val key = SealKey(ByteArray(32) { it.toByte() })
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId, key = key)))
        val chunkBlobs = (0 until 3).map { i ->
            val env = h.depositChunk(transferId, i, key = key)
            assertIs<InboundHandleResult.Success>(h.handler.handle(env))
            env.content
        }
        h.awaitSentSize(1)
        // The stored seal is replayed in the forward's OFFER content.
        val forward = h.transfers.byRelayTransfer(transferId).single()
        assertEquals(h.targetId, forward.peer)
        val marker = h.sent.single()
        val offerHeader = FileHeader.decode(marker.first.routingHeader) as FileHeader.Offer
        assertEquals(forward.transferId, offerHeader.transferId)
        assertEquals(h.targetId, offerHeader.finalTarget)

        // Marker ACKed → every stored chunk streams from the store.
        h.engine.onOutcome(
            OutboxOutcome.Acked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, forward.transferId.value),
                h.targetId,
                marker.first.packetId
            )
        )
        h.awaitSentSize(1 + 3)
        assertEquals(1 + 3, h.sent.size, "marker plus one row per stored chunk")
        val forwardedChunks = h.sent.drop(1)
        val forwardedBlobs = forwardedChunks.map { it.first.content }
        assertEquals(chunkBlobs.size, forwardedBlobs.size)
        assertTrue(
            chunkBlobs.all { want -> forwardedBlobs.any { got -> got.contentEquals(want) } },
            "chunk blobs are replayed exactly as they arrived",
        )
        // Per-chunk target ACKs drain the rows; the transfer is forgotten when drained.
        for ((env, _) in forwardedChunks) {
            h.engine.onOutcome(
                OutboxOutcome.Acked(
                    OutboxTag(OutboxTag.Kind.FILE_TRANSFER, forward.transferId.value),
                    h.targetId,
                    env.packetId
                )
            )
        }
        h.awaitForwardGone(transferId)
        assertNull(h.relay.find(transferId), "the relay forgets after delivery")
    }

    @Test
    fun targetDeclinedForward_dropsTransferAndFreesQuota() = runTest {
        val h = harness()
        h.engine.runIn(backgroundScope)
        h.forwards.runIn(backgroundScope)
        val transferId = h.depositWhole()
        h.awaitSentSize(1)
        val forward = h.transfers.byRelayTransfer(transferId).single()
        h.engine.onOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, forward.transferId.value),
                h.targetId,
                h.sent.single().first.packetId,
                PacketNackReason.DECLINED
            )
        )
        h.awaitForwardGone(transferId)
        assertNull(h.relay.find(transferId), "a refused forward is dropped, never re-offered")
    }

    @Test
    fun forwardingRelay_neverDepositsElsewhere() = runTest {
        val h = harness()
        h.engine.runIn(backgroundScope)
        h.forwards.runIn(backgroundScope)
        val transferId = h.depositWhole()
        h.awaitSentSize(1)
        val forward = h.transfers.byRelayTransfer(transferId).single()
        // The forward goes to the ultimate target, never to another relay.
        assertEquals(h.targetId, forward.peer)
        assertTrue(
            h.transfers.live()
                .none { it.peer == h.relayId || it.peer == authorId && it.transferId != forward.transferId && it.relayTransferId == null })
    }

    @Test
    fun oversizeOffer_refusedWithoutEvicting() = runTest {
        val h = harness(pledgeBytes = 4L * chunkSize)
        val first = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(first)))
        // A deposit that fits nowhere, even after evicting everything, is DECLINED.
        val huge = TransferId(Uuid.random())
        val result = h.handler.handle(
            h.depositOffer(
                huge,
                total = 5L * chunkSize,
                shape = TransferShape(chunkSize, 0, 5L * chunkSize)
            )
        )
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, result.reason)
        assertNotNull(h.relay.find(first), "a refused admission never disturbs the store")
        assertNull(h.relay.find(huge))
    }

    @Test
    fun evictionMidDeposit_declinedAtNextChunk() = runTest {
        val h = harness(pledgeBytes = 4L * chunkSize)
        val first = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(first)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(first, 0)))

        val second = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(second)))
        assertNull(h.relay.find(first), "the evicted mid-deposit transfer is gone")
        assertNotNull(h.relay.find(second))

        val result = h.handler.handle(h.depositChunk(first, 1))
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, result.reason)
    }

    @Test
    fun chunkOfTheWrongLength_isDeclined() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        val oversized = h.handler.handle(h.depositChunk(transferId, 0, plaintextBytes = chunkSize + 1))
        assertEquals(PacketNackReason.DECLINED, assertIs<InboundHandleResult.Rejected>(oversized).reason)
        val short = h.handler.handle(h.depositChunk(transferId, 1, plaintextBytes = 100))
        assertEquals(PacketNackReason.DECLINED, assertIs<InboundHandleResult.Rejected>(short).reason)
        assertEquals(0, h.relay.chunkIndices(transferId).size)
    }

    @Test
    fun offerChunkedBelowTheMinimum_isDeclined() = runTest {
        val h = harness()
        val result = h.handler.handle(
            h.depositOffer(TransferId(Uuid.random()), shape = TransferShape(1, 0, totalBytes))
        )
        assertEquals(PacketNackReason.DECLINED, assertIs<InboundHandleResult.Rejected>(result).reason)
        assertTrue(h.relay.all().isEmpty())
    }

    @Test
    fun duplicateOfferAndChunk_noop() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val offer = h.depositOffer(transferId)
        assertIs<InboundHandleResult.Success>(h.handler.handle(offer))
        assertIs<InboundHandleResult.Success>(h.handler.handle(offer))
        val chunk = h.depositChunk(transferId, 0)
        assertIs<InboundHandleResult.Success>(h.handler.handle(chunk))
        assertIs<InboundHandleResult.Success>(h.handler.handle(chunk))
        assertEquals(1, h.relay.chunkIndices(transferId).size)
    }

    @Test
    fun largeOffer_declinedAtDoor() = runTest {
        val h = harness()
        // Shape exceeds the small-file range; the seal itself stays valid so the door declines on shape.
        val result = h.handler.handle(h.depositOffer(shape = TransferShape(chunkSize, 0, SMALL_FILE_MAX_BYTES + 1)))
        assertIs<InboundHandleResult.Rejected>(result, "large files never touch relays")
        assertTrue(h.relay.all().isEmpty())
    }

    @Test
    fun expiredRelayTransfer_prunedOnSweep() = runTest {
        val h = harness()
        h.engine.runIn(backgroundScope)
        h.forwards.runIn(backgroundScope)
        val transferId = h.depositWhole()
        h.clock.advanceBy(3.days)
        // Sweep via a fresh forwards run tick: expire and drop.
        withTimeout(10.seconds) {
            while (h.relay.find(transferId) != null) {
                // Drive sweep manually by calling drop path through forwards run loop tick:
                // advance clock and let the sweep loop (chunkAckTimeout tick) fire.
                delay(100.milliseconds)
            }
        }
        assertNull(h.relay.find(transferId), "reservations die with the transfer TTL")
    }

    @Test
    fun idlePartialTransfer_droppedAfterIdleTtl() = runTest {
        val h = harness()
        h.engine.runIn(backgroundScope)
        h.forwards.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(transferId, 0)))
        assertEquals(FileRelayStatus.RESERVED, h.relay.find(transferId)?.status)
        h.clock.advanceBy(2.seconds + RouterConfig().fileRelayIdleTtl)
        withTimeout(120.seconds) {
            while (h.relay.find(transferId) != null) delay(100.milliseconds)
        }
        assertNull(h.relay.find(transferId), "an idle partial transfer is dropped")
    }

    @Test
    fun cancelDropsDeposit() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(transferId, 0)))
        val cancel = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = authorId,
            target = relayId,
            routingHeader = FileHeader.Cancel(transferId, org.yapyap.protocol.envelopes.FileCancelReason.ABORT)
                .encode(),
            content = ByteArray(0),
            signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
        )
        assertIs<InboundHandleResult.Success>(h.handler.handle(cancel))
        assertNull(h.relay.find(transferId))
    }
}
