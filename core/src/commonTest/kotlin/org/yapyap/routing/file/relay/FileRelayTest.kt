package org.yapyap.routing.file.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.e2ee.managerForPeer
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.protection.FakeIdentityResolverForProtection
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.PlaintextMessageProtection
import org.yapyap.protection.envelope.PlaintextSystemProtection
import org.yapyap.protection.envelope.PlaintextWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedAndEncryptedFileProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FileOutbox
import org.yapyap.routing.inbound.handlers.FileInboundHandler
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryFileIncomingTransferStore
import org.yapyap.routing.router.InMemoryFileRelayStore
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.NoopFilePushOps
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RefusingFilePorts
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RelayBackup
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
        val relay: InMemoryFileRelayStore,
        val forwarder: FileRelayForwarder,
        val sent: MutableList<Pair<BinaryEnvelope, OutboxTag?>>,
        val authorProtections: SignedAndEncryptedFileProtection,
        val authorId: PeerId,
        val relayId: PeerId,
        val targetId: PeerId,
        val clock: FakeClock,
    )

    private class RecordingRelayOutbox(val sent: MutableList<Pair<BinaryEnvelope, OutboxTag?>>) : FileOutbox {
        override suspend fun enqueue(envelope: BinaryEnvelope, tag: OutboxTag, relayBackup: RelayBackup) {
            sent += envelope to tag
        }

        override suspend fun enqueueDirect(envelope: BinaryEnvelope) {
            sent += envelope to null
        }

        override suspend fun deleteByTag(tag: OutboxTag) {
            sent.removeAll { it.second == tag }
        }

        override suspend fun hasQueued(tag: OutboxTag): Boolean = sent.any { it.second == tag }

        override suspend fun isPacketQueued(packetId: Uuid): Boolean = sent.any { it.first.packetId == packetId }
    }

    private suspend fun harness(
        pledgeBytes: Long = 100L * 1024 * 1024,
    ): Harness {
        val authorPeer = buildTestPeerIdentity(crypto, "relay-author")
        val relayPeer = buildTestPeerIdentity(crypto, "relay-node")
        val targetPeer = buildTestPeerIdentity(crypto, "relay-target")
        // The author seals OFFER halves to the ultimate target: it needs a session there.
        managerForPeer(crypto, authorPeer, targetPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))
        managerForPeer(crypto, targetPeer, authorPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto))

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
        // The relay verifies the author's signature (door, §7.1) but never opens the
        // sealed half — no session to anyone is needed for that.
        val relayProtection = SignedAndEncryptedFileProtection(
            FakeIdentityResolverForProtection(
                relayPeer.signingPrivateKey,
                mapOf(authorPeer.device.deviceId to authorPeer.device),
            ).let { DefaultSignatureProvider(it, it, crypto) },
            managerForPeer(crypto, relayPeer, authorPeer, MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto)),
            crypto,
        )
        val relayService = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = PlaintextWebRtcSignalProtection(crypto),
            fileProtection = relayProtection,
            messageProtection = PlaintextMessageProtection(crypto),
            systemProtection = PlaintextSystemProtection(crypto),
            bootstrapProtection = BootstrapProtection(crypto, org.yapyap.crypto.store.BootstrapKeySource { null }),
        )
        val clock = FakeClock(now)
        val config = RouterConfig(relayStoragePledgeBytes = pledgeBytes)
        val ctx = RoutingContext(
            localIdentity = FakeIdentityResolverForRouter(localDevice = relayPeer.device),
            localDeviceId = relayPeer.device.deviceId,
            devices = FakeIdentityResolverForRouter(localDevice = relayPeer.device),
            routes = FakeIdentityResolverForRouter(localDevice = relayPeer.device),
            packetDeduplicator = org.yapyap.routing.router.InMemoryPacketDeduplicator(),
            envelopeProtectionService = relayService,
            torTransport = RecordingTorTransport(),
            webRtcTransport = RecordingWebRtcTransport(),
            clock = clock,
            routerConfig = MutableStateFlow(config),
            transportLimits = MutableStateFlow(testTransportLimits()),
        )
        val relay = InMemoryFileRelayStore()
        val sent = mutableListOf<Pair<BinaryEnvelope, OutboxTag?>>()
        val forwarder = FileRelayForwarder(ctx, relay, RecordingRelayOutbox(sent))
        val handler = FileInboundHandler(
            ctx = ctx,
            incoming = InMemoryFileIncomingTransferStore(),
            ingest = RefusingFilePorts(),
            serveGate = RefusingFilePorts(),
            pusher = NoopFilePushOps(),
            relay = relay,
            onCoverageAck = { _, _, _ -> },
            onTransferComplete = {},
            onTransferCancelled = { _, _ -> },
            onOfferReceived = {},
            onRelayStored = { forwarder.wake() },
        )
        return Harness(
            handler, relay, forwarder, sent, authorProtection,
            authorPeer.device.deviceId, relayPeer.device.deviceId, targetPeer.device.deviceId, clock,
        )
    }

    private suspend fun Harness.depositOffer(
        transferId: TransferId = TransferId(Uuid.random()),
        total: Long = totalBytes,
        startIndex: Int = 0,
        key: ByteArray = ByteArray(32) { it.toByte() },
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
            EnvelopeProtectContext(now, authorId, targetId, SignalSecurityScheme.ENCRYPTED_AND_SIGNED, transferId),
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
        key: ByteArray = ByteArray(32) { it.toByte() },
    ): BinaryEnvelope {
        val ciphertext =
            authorProtections.encryptChunk(transferId, chunkIndex, key, ByteArray(100) { chunkIndex.toByte() })
        val inner = authorProtections.protect(
            FilePayload.EncryptedChunk(chunkIndex, ciphertext),
            EnvelopeProtectContext(now, authorId, targetId, SignalSecurityScheme.ENCRYPTED_AND_SIGNED, transferId),
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
        assertEquals(FileRelayStatus.WHOLE, relay.find(transferId)?.status)
        return transferId
    }

    @Test
    fun badSignature_isNeverStored() = runTest {
        val h = harness()
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
    fun chunkForUnknownTransfer_isDeclined() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val result = h.handler.handle(h.depositChunk(transferId, 0))
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, (result as InboundHandleResult.Rejected).reason)
        assertTrue(h.relay.live().isEmpty())
    }

    @Test
    fun chunkOutOfRange_isDeclined() = runTest {
        val h = harness()
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
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(transferId, 0)))
        assertEquals(FileRelayStatus.RESERVED, h.relay.find(transferId)?.status)
        h.forwarder.sweep()
        assertTrue(h.sent.isEmpty(), "an incomplete transfer is never pushed or offered")
    }

    @Test
    fun wholeTransfer_forwardsMarkerThenChunksVerbatim() = runTest {
        val h = harness()
        val key = ByteArray(32) { it.toByte() }
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId, key = key)))
        val chunkBlobs = (0 until 3).map { i ->
            val env = h.depositChunk(transferId, i, key)
            assertIs<InboundHandleResult.Success>(h.handler.handle(env))
            env.payload
        }
        h.forwarder.sweep()

        // The stored OFFER is replayed verbatim: outer source is the relay, the inner
        // source stays the original sender with its signature intact.
        val marker = h.sent.single { it.second == OutboxTag(OutboxTag.Kind.FILE, transferId.value) }
        assertEquals(h.targetId, marker.first.target)
        assertEquals(h.relayId, marker.first.source)
        val storedOffer = assertNotNull(h.relay.find(transferId)).offerPayload
        assertTrue(marker.first.payload.contentEquals(storedOffer), "the OFFER blob is replayed verbatim")

        // Marker ACKed → every stored chunk streams from the store.
        val markerPacket = marker.first.packetId
        h.forwarder.onOutboxOutcome(OutboxOutcome.Acked(marker.second!!, h.targetId, markerPacket))
        assertEquals(1 + 3, h.sent.size, "marker plus one row per stored chunk")
        val forwardedChunks = h.sent.drop(1)
        assertTrue(
            forwardedChunks.map { it.first.payload }.containsAll(chunkBlobs) &&
                    chunkBlobs.containsAll(forwardedChunks.map { it.first.payload }),
            "chunk blobs are replayed exactly as they arrived",
        )
        assertTrue(forwardedChunks.all { it.first.target == h.targetId && it.first.source == h.relayId })

        // Per-chunk target ACKs drain the rows; the transfer is forgotten when drained.
        for ((env, _) in forwardedChunks) {
            h.forwarder.onOutboxOutcome(
                OutboxOutcome.Acked(
                    OutboxTag(OutboxTag.Kind.FILE, transferId.value),
                    h.targetId,
                    env.packetId
                )
            )
        }
        assertNull(h.relay.find(transferId), "the relay forgets after delivery (invariant 3)")
    }

    @Test
    fun targetDeclinedForward_dropsTransferAndFreesQuota() = runTest {
        val h = harness()
        val transferId = h.depositWhole()
        h.forwarder.sweep()
        val markerPacket = h.sent.single().first.packetId
        val before = h.relay.reservedBytes()
        assertTrue(before > 0)
        h.forwarder.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE, transferId.value),
                h.targetId,
                markerPacket,
                PacketNackReason.DECLINED
            )
        )
        assertNull(h.relay.find(transferId), "a refused forward is dropped, never re-offered")
        assertEquals(0L, h.relay.reservedBytes(), "the reservation is freed at once")
    }

    @Test
    fun forwardingRelay_neverRedeposits() = runTest {
        val h = harness()
        val transferId = h.depositWhole()
        h.forwarder.sweep()
        val markerPacket = h.sent.single().first.packetId
        h.forwarder.onOutboxOutcome(
            OutboxOutcome.Acked(
                OutboxTag(OutboxTag.Kind.FILE, transferId.value),
                h.targetId,
                markerPacket
            )
        )
        val forwarded = h.sent.toList()
        for ((env, _) in forwarded.drop(1)) {
            h.forwarder.onOutboxOutcome(
                OutboxOutcome.Acked(
                    OutboxTag(OutboxTag.Kind.FILE, transferId.value),
                    h.targetId,
                    env.packetId
                )
            )
        }
        // Every outer envelope the relay emitted goes to the ultimate target with the
        // author's inner source — never to another relay (one jump, ever, invariant 2).
        assertTrue(forwarded.isNotEmpty())
        assertTrue(forwarded.all { it.first.target == h.targetId })
        for ((env, _) in forwarded) {
            val inner = org.yapyap.protocol.envelopes.FileEnvelope.decode(env.payload)
            assertEquals(h.authorId, inner.source, "the original signature rides verbatim")
        }
        assertTrue(h.relay.live().isEmpty())
    }

    @Test
    fun evictionMidDeposit_declinedAtNextChunk() = runTest {
        // Just enough pledge for one 3000-byte deposit.
        val h = harness(pledgeBytes = 4000L)
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
        val result = h.handler.handle(h.depositOffer(total = SMALL_FILE_MAX_BYTES + 1))
        assertIs<InboundHandleResult.Rejected>(result, "large files never touch relays (§5)")
        assertTrue(h.relay.live().isEmpty())
    }

    @Test
    fun prunedOutboxWhileDown_restreamedOnSweep() = runTest {
        val h = harness()
        val transferId = h.depositWhole()
        h.forwarder.sweep()
        val marker = h.sent.single()
        h.forwarder.onOutboxOutcome(OutboxOutcome.Acked(marker.second!!, h.targetId, marker.first.packetId))
        assertEquals(1 + 3, h.sent.size, "marker plus one row per stored chunk")

        // Outbox rows pruned while this relay was down (their ACKs never observed):
        // stored packet ids no longer resolve, so the sweep re-streams them.
        val chunkPackets = h.sent.drop(1).map { it.first.packetId }.toSet()
        h.sent.removeAll { (env, _) -> env.packetId in chunkPackets }
        h.forwarder.sweep()

        assertEquals(1 + 3, h.sent.size, "missing chunk rows are re-queued, nothing duplicated")
        assertEquals(FileRelayStatus.STREAMING, assertNotNull(h.relay.find(transferId)).status)
        // Draining the re-streamed rows still forgets the transfer.
        for ((env, _) in h.sent.drop(1)) {
            h.forwarder.onOutboxOutcome(
                OutboxOutcome.Acked(
                    OutboxTag(OutboxTag.Kind.FILE, transferId.value),
                    h.targetId,
                    env.packetId
                )
            )
        }
        assertNull(h.relay.find(transferId))
    }

    @Test
    fun expiredRelayTransfer_prunedOnSweep() = runTest {
        val h = harness()
        val transferId = h.depositWhole()
        h.clock.advanceBy(3.days)
        h.forwarder.sweep()
        assertNull(h.relay.find(transferId), "reservations die with the transfer TTL")
        assertTrue(h.sent.isEmpty(), "the forward's outbox rows go with it")
    }

    @Test
    fun cancelDropsDeposit() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositOffer(transferId)))
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.depositChunk(transferId, 0)))
        val inner = org.yapyap.protocol.envelopes.FileEnvelope.decode(h.depositOffer(TransferId(Uuid.random())).payload)
        // A CANCEL for the stored transferId: drop + ACK so the sender stops.
        val cancelInner = h.authorProtections.protect(
            FilePayload.Cancel(org.yapyap.protocol.envelopes.FileCancelReason.ABORT, null),
            EnvelopeProtectContext(now, h.authorId, h.targetId, SignalSecurityScheme.ENCRYPTED_AND_SIGNED, transferId),
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
