package org.yapyap.routing.file.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.e2ee.managerForPeer
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protection.FakeIdentityResolverForProtection
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.FileProtectContext
import org.yapyap.protection.envelope.PlaintextMessageProtection
import org.yapyap.protection.envelope.PlaintextSystemProtection
import org.yapyap.protection.envelope.PlaintextWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedAndEncryptedFileProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.TransferKey
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.ChunkRange
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FileIncomingTransfers
import org.yapyap.routing.file.FileTransferSignals
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.push.FileOutbox
import org.yapyap.routing.file.push.FilePushEngine
import org.yapyap.routing.inbound.handlers.FileInboundHandler
import org.yapyap.routing.inbound.handlers.FileRelayDoor
import org.yapyap.routing.inbound.handlers.FileServeHandler
import org.yapyap.routing.inbound.handlers.FileTargetHandler
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.router.FakeFilePlaintextSource
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.NoopFilePushOps
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RefusingFilePorts
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.store.DefaultFileIncomingTransferStore
import org.yapyap.routing.store.DefaultFilePushStore
import org.yapyap.routing.store.DefaultFileRelayStore
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.RetryClass
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
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
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Step 18: the relay door and the forward driver (design §7).
 *
 * The relay is dumb to content: signature-checked at the door, ciphertext stored
 * verbatim, forwarded only when whole. Nothing here touches the orchestrator —
 * no ingest, no carrier events, no account vocabulary.
 */
class FileRelayTest {

    private val crypto = DefaultCryptoProvider()
    private val now = epochSeconds(10_000L)
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val chunkSize = 1000
    private val totalBytes = 3000L // 3 chunks of 1000

    private inner class Harness(
        val handler: FileInboundHandler,
        val relay: FileRelayStore,
        val engine: FilePushEngine,
        val sent: MutableList<Pair<BinaryEnvelope, OutboxTag?>>,
        val authorProtections: SignedAndEncryptedFileProtection,
        val strangerProtections: SignedAndEncryptedFileProtection,
        val authorId: PeerId,
        val relayId: PeerId,
        val targetId: PeerId,
        val strangerId: PeerId,
        val clock: FakeClock,
    )

    private suspend fun Harness.awaitForwardGone(transferId: TransferId) {
        withTimeout(5_000) { while (relay.find(transferId) != null) delay(10) }
    }

    private suspend fun Harness.awaitSentSize(size: Int) {
        withTimeout(5_000) { while (sent.size < size) delay(10) }
    }

    private class RecordingRelayOutbox(val sent: MutableList<Pair<BinaryEnvelope, OutboxTag?>>) : FileOutbox {
        override suspend fun enqueue(
            envelope: BinaryEnvelope,
            tag: OutboxTag,
            relayBackup: RelayBackup,
            retryClass: RetryClass
        ) {
            sent += envelope to tag
        }

        override suspend fun enqueueDirect(envelope: BinaryEnvelope) {
            sent += envelope to null
        }

        override suspend fun deleteByTag(tag: OutboxTag) {
            sent.removeAll { it.second == tag }
        }

        override suspend fun packetIdsByTag(tag: OutboxTag): List<Uuid> =
            sent.mapNotNull { (env, t) -> if (t == tag) env.packetId else null }
    }

    private suspend fun harness(
        pledgeBytes: Long = 100L * 1024 * 1024,
        bannedAccounts: Set<AccountId> = emptySet(),
    ): Harness {
        val authorPeer = buildTestPeerIdentity(crypto, "relay-author")
        val relayPeer = buildTestPeerIdentity(crypto, "relay-node")
        val targetPeer = buildTestPeerIdentity(crypto, "relay-target")
        val strangerPeer = buildTestPeerIdentity(crypto, "relay-stranger")
        // The author seals OFFER halves to the ultimate target: it needs a session there.
        managerForPeer(crypto, authorPeer, targetPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        managerForPeer(crypto, targetPeer, authorPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        managerForPeer(crypto, strangerPeer, targetPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))

        val authorProtection = SignedAndEncryptedFileProtection(
            FakeIdentityResolverForProtection(authorPeer.signingPrivateKey, emptyMap())
                .let { DefaultSignatureProvider(it, it, crypto) },
            managerForPeer(
                crypto,
                authorPeer,
                targetPeer,
                MapBackedCryptoSessionStore(),
                InMemoryOpkRepository(crypto)
            ),
            crypto,
        )
        // The relay verifies signatures at the door (§7.1) but never opens the
        // sealed half — no session to anyone is needed for that.
        val relayProtection = SignedAndEncryptedFileProtection(
            FakeIdentityResolverForProtection(
                relayPeer.signingPrivateKey,
                mapOf(
                    authorPeer.device.deviceId to authorPeer.device,
                    strangerPeer.device.deviceId to strangerPeer.device,
                ),
            ).let { DefaultSignatureProvider(it, it, crypto) },
            managerForPeer(crypto, relayPeer, authorPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto)),
            crypto,
        )
        val strangerProtections = SignedAndEncryptedFileProtection(
            FakeIdentityResolverForProtection(strangerPeer.signingPrivateKey, emptyMap())
                .let { DefaultSignatureProvider(it, it, crypto) },
            managerForPeer(
                crypto,
                strangerPeer,
                targetPeer,
                MapBackedCryptoSessionStore(),
                InMemoryOpkRepository(crypto)
            ),
            crypto,
        )
        val relayService = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = PlaintextWebRtcSignalProtection(crypto),
            messageProtection = PlaintextMessageProtection(crypto),
            systemProtection = PlaintextSystemProtection(crypto),
            bootstrapProtection = BootstrapProtection(crypto, BootstrapKeySource { null }),
        )
        val clock = FakeClock(now)
        val config = RouterConfig(relayStoragePledgeBytes = pledgeBytes)
        val relayIdentity = FakeIdentityResolverForRouter(
            localDevice = relayPeer.device,
            peersByAccount = mapOf(
                AccountId("author-acct") to listOf(authorPeer.device.deviceId),
                AccountId("target-acct") to listOf(targetPeer.device.deviceId),
            ),
            accountStatuses = bannedAccounts.associateWith { IdentityStatus.BANNED }.toMutableMap(),
        )
        val ctx = RoutingContext(
            localIdentity = FakeIdentityResolverForRouter(localDevice = relayPeer.device),
            localDeviceId = relayPeer.device.deviceId,
            devices = relayIdentity,
            routes = FakeIdentityResolverForRouter(localDevice = relayPeer.device),
            packetDeduplicator = InMemoryPacketDeduplicator(),
            envelopeProtectionService = relayService,
            torTransport = RecordingTorTransport(),
            webRtcTransport = RecordingWebRtcTransport(),
            clock = clock,
            routerConfig = MutableStateFlow(config),
            transportLimits = MutableStateFlow(testTransportLimits()),
        )
        val relay = DefaultFileRelayStore(openMemoryDatabase().database, Dispatchers.Unconfined)
        val sent = mutableListOf<Pair<BinaryEnvelope, OutboxTag?>>()
        val pushes = DefaultFilePushStore(openMemoryDatabase().database, Dispatchers.Unconfined)
        val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val plaintext = FakeFilePlaintextSource()
        val incomingTransfers = FileIncomingTransfers(
            DefaultFileIncomingTransferStore(openMemoryDatabase().database, Dispatchers.Unconfined), clock,
        )
        val engine = FilePushEngine(
            ctx = ctx,
            protection = relayProtection,
            plaintext = plaintext,
            pushes = pushes,
            outbox = RecordingRelayOutbox(sent),
            events = events,
            relaySelection = NoRelaySelection,
            relay = relay,
            incoming = incomingTransfers,
        )
        val packetOutbox = TrackingPacketOutbox()
        val handler = FileInboundHandler(
            ctx = ctx,
            protection = relayProtection,
            target = FileTargetHandler(
                ctx, relayProtection, incomingTransfers, RefusingFilePorts(),
                signals = object : FileTransferSignals {
                    override suspend fun coverageAck(
                        transferId: TransferId,
                        from: PeerId,
                        highestContiguousIndex: Int,
                        missing: List<ChunkRange>,
                    ) = Unit

                    override suspend fun complete(transferId: TransferId, from: PeerId) = Unit
                    override suspend fun cancelled(
                        transferId: TransferId,
                        from: PeerId,
                        reason: FileCancelReason,
                    ) = Unit
                },
                onOfferReceived = {},
            ),
            door = FileRelayDoor(ctx, relayProtection, relay, packetOutbox, onRelayWhole = { engine.onRelayWhole(it) }),
            serve = FileServeHandler(ctx, RefusingFilePorts(), NoopFilePushOps()),
        )
        return Harness(
            handler, relay, engine, sent, authorProtection, strangerProtections,
            authorPeer.device.deviceId, relayPeer.device.deviceId, targetPeer.device.deviceId,
            strangerPeer.device.deviceId, clock,
        )
    }

    private suspend fun Harness.depositOffer(
        transferId: TransferId = TransferId(Uuid.random()),
        total: Long = totalBytes,
        startIndex: Int = 0,
        key: TransferKey = TransferKey(ByteArray(32) { it.toByte() }),
    ): BinaryEnvelope {
        val sealed = authorProtections.sealOffer(
            FilePayload.SealedOffer(
                fileId = fileId,
                blockSize = 1024 * 1024,
                blockHashes = listOf(ByteArray(32) { 1 }),
                transferKey = key,
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
            ),
            targetId,
        )
        val inner = authorProtections.protect(
            FilePayload.Offer(chunkSize, total, startIndex, sealed),
            FileProtectContext(authorId, targetId, now, transferId),
        )
        return BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = authorId,
            target = relayId,
            payload = inner.encode(),
        )
    }

    private suspend fun Harness.depositChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: TransferKey = TransferKey(ByteArray(32) { it.toByte() }),
    ): BinaryEnvelope {
        val ciphertext =
            authorProtections.encryptChunk(transferId, chunkIndex, key, ByteArray(100) { chunkIndex.toByte() })
        val inner = authorProtections.protect(
            FilePayload.EncryptedChunk(chunkIndex, ciphertext),
            FileProtectContext(authorId, targetId, now, transferId),
        )
        return BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = authorId,
            target = relayId,
            payload = inner.encode(),
        )
    }

    private suspend fun Harness.depositWhole(
        transferId: TransferId = TransferId(Uuid.random()),
    ): TransferId {
        assertIs<InboundHandleResult.Success>(handler.handle(depositOffer(transferId)))
        for (i in 0 until 3) {
            assertIs<InboundHandleResult.Success>(handler.handle(depositChunk(transferId, i)))
        }
        // The door promotes to WHOLE; the engine's sweep (via onRelayStored) starts the
        // forward at once, so the row may already be FORWARDING/STREAMING here.
        val status = relay.find(transferId)?.status
        assertTrue(
            status == FileRelayStatus.WHOLE ||
                    status == FileRelayStatus.FORWARDING ||
                    status == FileRelayStatus.STREAMING,
            "expected WHOLE (or an already-starting forward), got $status",
        )
        return transferId
    }

    @Test
    fun concurrentForwardStarts_sendOneMarker() = runTest {
        val h = harness()
        // Deposit while the engine is stopped: nothing starts, the row waits WHOLE.
        val transferId = h.depositWhole()
        assertTrue(h.sent.isEmpty(), "a stopped engine starts no forward")

        // The door's WHOLE signal and the tick sweep racing each other start one actor.
        h.engine.runIn(backgroundScope)
        coroutineScope {
            repeat(20) {
                launch { h.engine.onRelayWhole(transferId) }
                launch { h.engine.sweepForwards() }
            }
        }
        val forwardTag = OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value)
        assertEquals(1, h.sent.count { it.second == forwardTag }, "one forward, one marker")
    }

    @Test
    fun badSignature_isNeverStored() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val bad = h.depositOffer().let { env ->
            val tampered = env.payload.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            env.copy(payload = tampered)
        }
        val result = h.handler.handle(bad)
        assertIs<InboundHandleResult.Rejected>(result, "a bad signature is dropped at the door")
        assertNull(h.relay.find(TransferId(Uuid.random())), "sanity: unknown transfer absent")
        assertTrue(h.relay.live().isEmpty(), "nothing is stored from an unverified envelope")
    }

    @Test
    fun bannedAccountInnerSource_isDeclinedWithoutStoring() = runTest {
        // The author's device is active, but its account is banned (design §7.1).
        val h = harness(bannedAccounts = setOf(AccountId("author-acct")))

        h.engine.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        val rejected = assertIs<InboundHandleResult.Rejected>(h.handler.handle(h.depositOffer(transferId)))
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertNull(h.relay.find(transferId), "a banned account's deposit stores nothing")
    }

    @Test
    fun unknownInnerSource_isDeclinedWithoutStoring() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        // The stranger's signature verifies at the door, but it belongs to no known account:
        // the inner-source check (design §7.1) declines before anything is stored.
        val transferId = TransferId(Uuid.random())
        val key = TransferKey(ByteArray(32) { it.toByte() })
        val sealed = h.strangerProtections.sealOffer(
            FilePayload.SealedOffer(
                fileId = fileId,
                blockSize = 1024 * 1024,
                blockHashes = listOf(ByteArray(32) { 1 }),
                transferKey = key,
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
            ),
            h.targetId,
        )
        val inner = h.strangerProtections.protect(
            FilePayload.Offer(chunkSize, totalBytes, 0, sealed),
            FileProtectContext(h.strangerId, h.targetId, now, transferId),
        )
        val env = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = h.strangerId,
            target = h.relayId,
            payload = inner.encode(),
        )
        val result = h.handler.handle(env)
        val rejected = assertIs<InboundHandleResult.Rejected>(result, "unknown inner source is declined")
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertNull(h.relay.find(transferId), "a declined admission stores nothing")
    }

    @Test
    fun chunkForUnknownTransfer_isDeclined() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        val result = h.handler.handle(h.depositChunk(transferId, 0))
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, (result as InboundHandleResult.Rejected).reason)
        assertTrue(h.relay.live().isEmpty())
    }

    @Test
    fun chunkOutOfRange_isDeclined() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        val result = h.handler.handle(h.depositChunk(transferId, 7))
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, (result as InboundHandleResult.Rejected).reason)
        assertEquals(0L, h.relay.chunkCount(transferId), "out-of-range bytes never count toward wholeness")
    }

    @Test
    fun partialTransfer_isNeverForwarded() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(transferId, 0)))
        assertEquals(FileRelayStatus.RESERVED, h.relay.find(transferId)?.status)
        h.engine.sweepForwards()
        assertTrue(h.sent.isEmpty(), "an incomplete transfer is never pushed or offered")
    }

    @Test
    fun wholeTransfer_forwardsMarkerThenChunksVerbatim() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val key = TransferKey(ByteArray(32) { it.toByte() })
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId, key = key)))
        val chunkBlobs = (0 until 3).map { i ->
            val env = h.depositChunk(transferId, i, key)
            assertIs<InboundHandleResult.Success>(h.handler.handle(env))
            env.payload
        }
        h.engine.sweepForwards()

        // The stored OFFER is replayed verbatim: outer source is the relay, the inner
        // source stays the original sender with its signature intact.
        val marker = h.sent.single { it.second == OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value) }
        assertEquals(h.targetId, marker.first.target)
        assertEquals(h.relayId, marker.first.source)
        val storedOffer = assertNotNull(h.relay.find(transferId)).offerPayload
        assertTrue(marker.first.payload.contentEquals(storedOffer), "the OFFER blob is replayed verbatim")

        // Marker ACKed → every stored chunk streams from the store.
        val markerPacket = marker.first.packetId
        h.engine.onForwardOutcome(OutboxOutcome.Acked(marker.second!!, h.targetId, markerPacket))
        h.awaitSentSize(1 + 3)
        assertEquals(1 + 3, h.sent.size, "marker plus one row per stored chunk")
        val forwardedChunks = h.sent.drop(1)
        val forwardedBlobs = forwardedChunks.map { it.first.payload }
        // Content comparison: the SQL store returns copies, so reference identity
        // (which the old in-memory fake preserved) no longer holds.
        assertEquals(chunkBlobs.size, forwardedBlobs.size)
        assertTrue(
            chunkBlobs.all { want -> forwardedBlobs.any { got -> got.contentEquals(want) } },
            "chunk blobs are replayed exactly as they arrived",
        )
        assertTrue(forwardedChunks.all { it.first.target == h.targetId && it.first.source == h.relayId })

        // Per-chunk target ACKs drain the rows; the transfer is forgotten when drained.
        for ((env, _) in forwardedChunks) {
            h.engine.onForwardOutcome(
                OutboxOutcome.Acked(
                    OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value),
                    h.targetId,
                    env.packetId
                )
            )
        }
        h.awaitForwardGone(transferId)
        assertNull(h.relay.find(transferId), "the relay forgets after delivery (invariant 3)")
    }

    @Test
    fun targetDeclinedForward_dropsTransferAndFreesQuota() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = h.depositWhole()
        h.engine.sweepForwards()
        val markerPacket = h.sent.single().first.packetId
        val before = h.relay.reservedBytes()
        assertTrue(before > 0)
        h.engine.onForwardOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value),
                h.targetId,
                markerPacket,
                PacketNackReason.DECLINED
            )
        )
        h.awaitForwardGone(transferId)
        assertNull(h.relay.find(transferId), "a refused forward is dropped, never re-offered")
        assertEquals(0L, h.relay.reservedBytes(), "the reservation is freed at once")
    }

    @Test
    fun forwardingRelay_neverRedeposits() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = h.depositWhole()
        h.engine.sweepForwards()
        val markerPacket = h.sent.single().first.packetId
        h.engine.onForwardOutcome(
            OutboxOutcome.Acked(
                OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value),
                h.targetId,
                markerPacket
            )
        )
        h.awaitSentSize(1 + 3)
        val forwarded = h.sent.toList()
        for ((env, _) in forwarded.drop(1)) {
            h.engine.onForwardOutcome(
                OutboxOutcome.Acked(
                    OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value),
                    h.targetId,
                    env.packetId
                )
            )
        }
        h.awaitForwardGone(transferId)
        // Every outer envelope the relay emitted goes to the ultimate target with the
        // author's inner source — never to another relay (one jump, ever, invariant 2).
        assertTrue(forwarded.isNotEmpty())
        assertTrue(forwarded.all { it.first.target == h.targetId })
        for ((env, _) in forwarded) {
            val inner = FileEnvelope.decode(env.payload)
            assertEquals(h.authorId, inner.source, "the original signature rides verbatim")
        }
        assertTrue(h.relay.live().isEmpty())
    }

    @Test
    fun oversizeOffer_refusedWithoutEvicting() = runTest {
        // Just enough pledge for one 3000-byte deposit.
        val h = harness(pledgeBytes = 4000L)
        val first = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(first)))
        assertEquals(3000L, h.relay.reservedBytes())

        // A deposit that fits nowhere, even after evicting everything, is DECLINED —
        // and the eviction it would need never happens.
        val huge = TransferId(Uuid.random())
        val result = h.handler.handle(h.depositOffer(huge, total = 5000L))
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, (result as InboundHandleResult.Rejected).reason)
        assertNotNull(h.relay.find(first), "a refused admission never disturbs the store")
        assertEquals(3000L, h.relay.reservedBytes())
        assertNull(h.relay.find(huge))
    }

    @Test
    fun evictionMidDeposit_declinedAtNextChunk() = runTest {
        // Just enough pledge for one 3000-byte deposit.
        val h = harness(pledgeBytes = 4000L)

        h.engine.runIn(backgroundScope)
        val first = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(first)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(first, 0)))

        // A second deposit evicts the first, oldest-first (RESERVED is not exempt).
        val second = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(second)))
        assertNull(h.relay.find(first), "the evicted mid-deposit transfer is gone")
        assertNotNull(h.relay.find(second))

        // The evicted sender learns it through the door DECLINED on its next chunk.
        val result = h.handler.handle(h.depositChunk(first, 1))
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, (result as InboundHandleResult.Rejected).reason)
    }

    @Test
    fun duplicateOfferAndChunk_noop() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        val offer = h.depositOffer(transferId)
        assertIs<InboundHandleResult.Success>(h.handler.handle(offer))
        // The sender retries until our ACK lands: storing twice must not double-count.
        assertIs<InboundHandleResult.Success>(h.handler.handle(offer))
        val chunk = h.depositChunk(transferId, 0)
        assertIs<InboundHandleResult.Success>(h.handler.handle(chunk))
        assertIs<InboundHandleResult.Success>(h.handler.handle(chunk))
        assertEquals(1L, h.relay.chunkCount(transferId))
    }

    @Test
    fun largeOffer_declinedAtDoor() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val result = h.handler.handle(h.depositOffer(total = SMALL_FILE_MAX_BYTES + 1))
        assertIs<InboundHandleResult.Rejected>(result, "large files never touch relays (§5)")
        assertTrue(h.relay.live().isEmpty())
    }

    @Test
    fun prunedOutboxWhileDown_restreamedOnSweep() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = h.depositWhole()
        h.engine.sweepForwards()
        val marker = h.sent.single()
        h.engine.onForwardOutcome(OutboxOutcome.Acked(marker.second!!, h.targetId, marker.first.packetId))
        h.awaitSentSize(1 + 3)
        assertEquals(1 + 3, h.sent.size, "marker plus one row per stored chunk")

        // Outbox rows pruned while this relay was down (their ACKs never observed):
        // stored packet ids no longer resolve, so the sweep re-streams them.
        val chunkPackets = h.sent.drop(1).map { it.first.packetId }.toSet()
        h.sent.removeAll { (env, _) -> env.packetId in chunkPackets }
        h.engine.sweepForwards()
        h.awaitSentSize(1 + 3)

        assertEquals(1 + 3, h.sent.size, "missing chunk rows are re-queued, nothing duplicated")
        assertEquals(FileRelayStatus.STREAMING, assertNotNull(h.relay.find(transferId)).status)
        // Draining the re-streamed rows still forgets the transfer.
        for ((env, _) in h.sent.drop(1)) {
            h.engine.onForwardOutcome(
                OutboxOutcome.Acked(
                    OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value),
                    h.targetId,
                    env.packetId
                )
            )
        }
        h.awaitForwardGone(transferId)
        assertNull(h.relay.find(transferId))
    }

    @Test
    fun expiredRelayTransfer_prunedOnSweep() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = h.depositWhole()
        h.clock.advanceBy(3.days)
        h.engine.sweepForwards()
        h.awaitForwardGone(transferId)
        assertNull(h.relay.find(transferId), "reservations die with the transfer TTL")
        assertTrue(h.sent.isEmpty(), "the forward's outbox rows go with it")
    }

    @Test
    fun idlePartialTransfer_droppedAfterIdleTtl() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(transferId, 0)))
        assertEquals(FileRelayStatus.RESERVED, h.relay.find(transferId)?.status)
        val reservedBefore = h.relay.reservedBytes()
        assertTrue(reservedBefore > 0)

        // A partial transfer that never completes frees its quota after the idle TTL.
        h.clock.advanceBy(2.hours)
        h.engine.sweepForwards()
        h.awaitForwardGone(transferId)
        assertNull(h.relay.find(transferId), "an idle partial transfer is dropped")
        assertEquals(0L, h.relay.reservedBytes(), "its bytes are freed")
    }

    @Test
    fun cancelFromNonDepositor_isIgnored() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(transferId, 0)))

        // A CANCEL from anyone but the depositor is ignored (still ACKed): the deposit stays.
        val cancelInner = h.strangerProtections.protect(
            FilePayload.Cancel(FileCancelReason.ABORT),
            FileProtectContext(h.strangerId, h.targetId, now, transferId),
        )
        val cancelOuter = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = h.strangerId,
            target = h.relayId,
            payload = cancelInner.encode(),
        )
        assertIs<InboundHandleResult.Success>(h.handler.handle(cancelOuter))
        assertNotNull(h.relay.find(transferId), "a CANCEL from a non-depositor never deletes the deposit")
    }

    @Test
    fun forwardComplete_forgetsTransfer() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = h.depositWhole()
        h.engine.sweepForwards()
        h.awaitSentSize(1)
        h.engine.complete(transferId, h.targetId)
        h.awaitForwardGone(transferId)
        assertNull(h.relay.find(transferId), "COMPLETE forgets the forward after delivery")

        // A COMPLETE from anyone but the target is ignored.
        val second = h.depositWhole()
        h.engine.sweepForwards()
        h.awaitSentSize(1)
        h.engine.complete(second, h.authorId)
        delay(200)
        assertNotNull(h.relay.find(second), "a COMPLETE from a non-target is ignored")
    }

    @Test
    fun cancelDropsDeposit() = runTest {
        val h = harness()

        h.engine.runIn(backgroundScope)
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(transferId, 0)))
        val inner = FileEnvelope.decode(h.depositOffer(TransferId(Uuid.random())).payload)
        // A CANCEL for the stored transferId: drop + ACK so the sender stops.
        val cancelInner = h.authorProtections.protect(
            FilePayload.Cancel(FileCancelReason.ABORT),
            FileProtectContext(h.authorId, h.targetId, now, transferId),
        )
        val cancelOuter = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = h.authorId,
            target = h.relayId,
            payload = cancelInner.encode(),
        )
        assertIs<InboundHandleResult.Success>(h.handler.handle(cancelOuter))
        assertNull(h.relay.find(transferId))
        assertEquals(inner.source, h.authorId, "sanity: test helper builds author-sourced envelopes")
    }
}
