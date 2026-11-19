package org.yapyap.routing.inbound.handlers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.e2ee.managerForPeer
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.protection.FakeIdentityResolverForProtection
import org.yapyap.protection.envelope.PlaintextMessageProtection
import org.yapyap.protection.envelope.PlaintextSystemProtection
import org.yapyap.protection.envelope.PlaintextWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedAndEncryptedFileProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FileCarrier
import org.yapyap.routing.file.FileCoverage
import org.yapyap.routing.file.FileEvent
import org.yapyap.routing.file.FileIngestPort
import org.yapyap.routing.file.FileIngestResult
import org.yapyap.routing.file.FileOfferInfo
import org.yapyap.routing.file.FileOfferVerdict
import org.yapyap.routing.file.FileRefusalReason
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryFileIncomingTransferStore
import org.yapyap.routing.router.InMemoryFileRelayStore
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.NoopFilePushOps
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingTransferStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

internal class RecordingFileCarrier : FileCarrier {
    val events = mutableListOf<FileEvent>()
    override val fileEvents: kotlinx.coroutines.flow.Flow<FileEvent>
        get() = error("RecordingFileCarrier exposes recorded events instead")

    override suspend fun pushFile(fileId: FileId, target: org.yapyap.protocol.AccountId): Unit =
        error("not used in inbound handler tests")

    override suspend fun cancelFile(fileId: FileId, target: org.yapyap.protocol.AccountId): Unit =
        error("not used in inbound handler tests")

    override suspend fun fileReady(fileId: FileId): Unit =
        error("not used in inbound handler tests")

    override suspend fun cancelIncoming(fileId: FileId, reason: org.yapyap.protocol.envelopes.FileCancelReason): Unit =
        error("not used in inbound handler tests")

    override suspend fun emit(event: FileEvent) {
        events += event
    }
}

internal class ScriptedFileIngestPort(
    var offerVerdict: FileOfferVerdict = FileOfferVerdict.Accept,
    var ingestResult: FileIngestResult = FileIngestResult.Staged(0, 0),
) : FileIngestPort {
    val offers = mutableListOf<FileOfferInfo>()
    val ingests = mutableListOf<Triple<FileId, Long, ByteArray>>()

    override suspend fun checkOffer(offer: FileOfferInfo): FileOfferVerdict {
        offers += offer
        return offerVerdict
    }

    override suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray): FileIngestResult {
        ingests += Triple(fileId, byteOffset, plaintext)
        return ingestResult
    }

    override suspend fun heldCoverage(fileId: FileId): FileCoverage? = null

    override suspend fun requestCandidates(fileId: FileId): List<AccountId> = emptyList()

    override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean = false
}

class FileInboundHandlerTest {

    private val crypto = DefaultCryptoProvider()
    private val now = epochSeconds(10_000L)
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    private data class Harness(
        val handler: InboundEnvelopeHandler,
        val senderProtection: SignedAndEncryptedFileProtection,
        val incoming: FileIncomingTransferStore,
        val ingest: ScriptedFileIngestPort,
        val carrier: RecordingFileCarrier,
        val localId: PeerId,
        val remoteId: PeerId,
        val clock: FakeClock,
        val coverageAcks: MutableList<Triple<TransferId, Int, List<org.yapyap.protocol.envelopes.ChunkRange>>>,
        val completed: MutableList<TransferId>,
        val cancelled: MutableList<TransferId>,
    )

    private suspend fun harness(
        ingest: ScriptedFileIngestPort = ScriptedFileIngestPort(),
    ): Harness {
        val senderPeer = buildTestPeerIdentity(crypto, "fh-sender")
        val receiverPeer = buildTestPeerIdentity(crypto, "fh-receiver")
        val senderSession = managerForPeer(
            crypto, senderPeer, receiverPeer,
            MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto),
        )
        val receiverSession = managerForPeer(
            crypto, receiverPeer, senderPeer,
            MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto),
        )
        val senderProtection = SignedAndEncryptedFileProtection(
            FakeIdentityResolverForProtection(senderPeer.signingPrivateKey, emptyMap())
                .let { DefaultSignatureProvider(it, it, crypto) },
            senderSession,
            crypto,
        )
        val receiverProtection = SignedAndEncryptedFileProtection(
            FakeIdentityResolverForProtection(
                receiverPeer.signingPrivateKey,
                mapOf(senderPeer.device.deviceId to senderPeer.device),
            ).let { DefaultSignatureProvider(it, it, crypto) },
            receiverSession,
            crypto,
        )
        val service = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = PlaintextWebRtcSignalProtection(crypto),
            fileProtection = receiverProtection,
            messageProtection = PlaintextMessageProtection(crypto),
            systemProtection = PlaintextSystemProtection(crypto),
            bootstrapProtection = org.yapyap.protection.envelope.BootstrapProtection(
                crypto,
                org.yapyap.crypto.store.BootstrapKeySource { null },
            ),
        )
        val local = receiverPeer.device
        val identity = FakeIdentityResolverForRouter(localDevice = local)
        val clock = FakeClock(now)
        val ctx = RoutingContext(
            localIdentity = identity,
            localDeviceId = local.deviceId,
            devices = identity,
            routes = identity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            envelopeProtectionService = service,
            torTransport = RecordingTorTransport(),
            webRtcTransport = RecordingWebRtcTransport(),
            clock = clock,
            routerConfig = MutableStateFlow(RouterConfig()),
            transportLimits = MutableStateFlow(testTransportLimits()),
        )
        val incoming = InMemoryFileIncomingTransferStore()
        val carrier = RecordingFileCarrier()
        val coverageAcks = mutableListOf<Triple<TransferId, Int, List<org.yapyap.protocol.envelopes.ChunkRange>>>()
        val completed = mutableListOf<TransferId>()
        val cancelled = mutableListOf<TransferId>()
        return Harness(
            handler = FileInboundHandler(
                ctx,
                incoming,
                ingest,
                carrier,
                pusher = NoopFilePushOps(),
                relay = InMemoryFileRelayStore(),
                onCoverageAck = { transferId, contiguous, missing -> coverageAcks += Triple(transferId, contiguous, missing) },
                onTransferComplete = { completed += it },
                onTransferCancelled = { cancelled += it },
                onOfferReceived = {},
                onRelayStored = {},
            ),
            senderProtection = senderProtection,
            incoming = incoming,
            ingest = ingest,
            carrier = carrier,
            localId = local.deviceId,
            remoteId = senderPeer.device.deviceId,
            clock = clock,
            coverageAcks = coverageAcks,
            completed = completed,
            cancelled = cancelled,
        )
    }

    private suspend fun Harness.offerEnvelope(
        transferId: TransferId = TransferId(Uuid.random()),
        chunkSize: Int = 220 * 1024,
        totalBytes: Long = 1000L,
        startIndex: Int = 0,
        key: ByteArray = ByteArray(32) { it.toByte() },
    ): BinaryEnvelope {
        val sealed = senderProtection.sealOffer(
            FilePayload.SealedOffer(
                fileId = fileId,
                blockSize = 1024 * 1024,
                blockHashes = listOf(ByteArray(32) { 1 }),
                transferKey = key,
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
            ),
            localId,
        )
        val envelope = senderProtection.protect(
            FilePayload.Offer(chunkSize, totalBytes, startIndex, sealed),
            EnvelopeProtectContext(now, remoteId, localId, SignalSecurityScheme.ENCRYPTED_AND_SIGNED, transferId),
        )
        return BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = remoteId,
            target = localId,
            payload = envelope.encode(),
        )
    }

    private suspend fun Harness.chunkEnvelope(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        plaintext: ByteArray = "chunk-$chunkIndex".encodeToByteArray(),
    ): BinaryEnvelope {
        val ciphertext = senderProtection.encryptChunk(transferId, chunkIndex, key, plaintext)
        val envelope = senderProtection.protect(
            FilePayload.EncryptedChunk(chunkIndex, ciphertext),
            EnvelopeProtectContext(now, remoteId, localId, SignalSecurityScheme.ENCRYPTED_AND_SIGNED, transferId),
        )
        return BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = remoteId,
            target = localId,
            payload = envelope.encode(),
        )
    }

    @Test
    fun offer_accepted_createsRowAndAcks() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val result = h.handler.handle(h.offerEnvelope(transferId))
        assertIs<InboundHandleResult.Success>(result)
        val row = h.incoming.find(transferId)
        assertTrue(row != null, "incoming-transfer row must exist")
        assertEquals(fileId, row.fileId)
        assertEquals(h.remoteId, row.authorDevice)
        assertEquals(220 * 1024, row.chunkSizeBytes)
        val offer = h.ingest.offers.single()
        assertEquals(fileId, offer.fileId)
        assertEquals("photo.jpg", offer.fileName)
    }

    @Test
    fun offer_forReadyFile_ackedWithCompleteAndNoRow() = runTest {
        val h = harness(ingest = ScriptedFileIngestPort(offerVerdict = FileOfferVerdict.AlreadyReady))
        val transferId = TransferId(Uuid.random())
        val result = h.handler.handle(h.offerEnvelope(transferId))
        val success = assertIs<InboundHandleResult.Success>(result)
        assertTrue(h.incoming.find(transferId) == null, "READY file gets no row")
        val reply = success.sideEffects.filterIsInstance<InboundSideEffect.EnqueueDirect>().single()
        val fileEnvelope = FileEnvelope.decode(reply.envelope.payload)
        assertEquals(transferId, fileEnvelope.transferId)
        assertEquals(h.remoteId, fileEnvelope.target)
        assertIs<FilePayload.Complete>(fileEnvelope.decodePayload())
    }

    @Test
    fun offer_refused_isDeclinedWithNoRow() = runTest {
        val h = harness(
            ingest = ScriptedFileIngestPort(offerVerdict = FileOfferVerdict.Refuse(FileRefusalReason.NO_SPACE)),
        )
        val result = h.handler.handle(h.offerEnvelope())
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
    }

    @Test
    fun offer_deferred_isSilent() = runTest {
        val h = harness(ingest = ScriptedFileIngestPort(offerVerdict = FileOfferVerdict.Defer))
        val result = h.handler.handle(h.offerEnvelope())
        assertIs<InboundHandleResult.Deferred>(result)
    }

    @Test
    fun chunk_beforeOffer_isNeverAcked() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        val result = h.handler.handle(h.chunkEnvelope(transferId, 0, key))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertTrue(h.ingest.ingests.isEmpty(), "unknown transferId: never stored, never ACKed")
    }

    @Test
    fun chunk_unknownTransferId_isDeclined() = runTest {
        val h = harness()
        // An OFFER for a different transfer exists; this chunk names one that was never offered.
        h.handler.handle(h.offerEnvelope(TransferId(Uuid.random())))
        val result = h.handler.handle(h.chunkEnvelope(TransferId(Uuid.random()), 0, ByteArray(32)))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertEquals(1, h.ingest.offers.size)
        assertTrue(h.ingest.ingests.isEmpty())
    }

    @Test
    fun chunk_staged_emitsProgressAndAcks() = runTest {
        val h = harness(
            ingest = ScriptedFileIngestPort(
                ingestResult = FileIngestResult.Staged(contiguousBytes = 440L * 1024, totalBytes = 1000L),
            ),
        )
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        h.handler.handle(h.offerEnvelope(transferId, totalBytes = 1024L * 1024))
        val plaintext = "chunk-0".encodeToByteArray()
        val result = h.handler.handle(h.chunkEnvelope(transferId, 0, key, plaintext))
        val success = assertIs<InboundHandleResult.Success>(result)
        val (id, offset, bytes) = h.ingest.ingests.single()
        assertEquals(fileId, id)
        assertEquals(0L, offset)
        assertContentEquals(plaintext, bytes)
        val progress = h.carrier.events.filterIsInstance<FileEvent.IncomingProgress>().single()
        assertEquals(fileId, progress.fileId)
        assertEquals(440L * 1024, progress.contiguousBytes)
        // A coverage ACK goes back to the transfer's pusher (own chunk indices).
        val ackReply = success.sideEffects.filterIsInstance<InboundSideEffect.EnqueueDirect>().single()
        val ackEnvelope = FileEnvelope.decode(ackReply.envelope.payload)
        assertEquals(transferId, ackEnvelope.transferId)
        assertEquals(h.remoteId, ackEnvelope.target)
        val ack = assertIs<FilePayload.Ack>(ackEnvelope.decodePayload())
        assertEquals(1, ack.contiguousChunks)
        // Coverage callbacks fire on the sender's side when this ACK arrives there,
        // not on the receiver that emitted it.
        assertTrue(h.coverageAcks.isEmpty())
    }

    @Test
    fun chunk_duplicate_isAcked() = runTest {
        val h = harness(ingest = ScriptedFileIngestPort(ingestResult = FileIngestResult.Duplicate))
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        h.handler.handle(h.offerEnvelope(transferId))
        val result = h.handler.handle(h.chunkEnvelope(transferId, 3, key))
        assertIs<InboundHandleResult.Success>(result)
    }

    @Test
    fun chunk_deferred_isSilent() = runTest {
        val h = harness(ingest = ScriptedFileIngestPort(ingestResult = FileIngestResult.Defer))
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        h.handler.handle(h.offerEnvelope(transferId))
        val result = h.handler.handle(h.chunkEnvelope(transferId, 0, key))
        assertIs<InboundHandleResult.Deferred>(result)
    }

    @Test
    fun chunk_refused_isDeclined() = runTest {
        val h = harness(ingest = ScriptedFileIngestPort(ingestResult = FileIngestResult.Refused))
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        h.handler.handle(h.offerEnvelope(transferId))
        val result = h.handler.handle(h.chunkEnvelope(transferId, 0, key))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
    }

    @Test
    fun chunk_tamperedSignature_isNeverAcked() = runTest {
        val h = harness()
        val env = h.chunkEnvelope(TransferId(Uuid.random()), 0, ByteArray(32))
        val fileEnv = FileEnvelope.decode(env.payload)
        val tampered = fileEnv.copy(signature = fileEnv.signature!!.copyOf().also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        })
        val bad = env.copy(payload = tampered.encode())
        val result = h.handler.handle(bad)
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.PERMANENT_PROTECTION_FAILED, rejected.reason)
        assertTrue(h.ingest.ingests.isEmpty())
    }

    @Test
    fun chunk_expiredRow_isDeclinedAndDropped() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        h.handler.handle(h.offerEnvelope(transferId))
        h.clock.advanceTo(h.clock.now() + 3.days)
        val result = h.handler.handle(h.chunkEnvelope(transferId, 0, key))
        assertIs<InboundHandleResult.Rejected>(result)
        assertTrue(h.incoming.find(transferId) == null, "expired rows are dropped")
    }

    @Test
    fun foreignTarget_isDeclined() = runTest {
        val h = harness()
        val env = h.offerEnvelope()
        val fileEnv = FileEnvelope.decode(env.payload)
        val foreign = env.copy(
            payload = fileEnv.copy(target = PeerId("ffff".repeat(16))).encode(),
        )
        val result = h.handler.handle(foreign)
        assertIs<InboundHandleResult.Rejected>(result)
    }

    @Test
    fun ack_forwardsCoverageToPusher() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val ack = FilePayload.Ack(contiguousChunks = 3, missingRanges = emptyList())
        val envelope = h.senderProtection.protect(
            ack,
            EnvelopeProtectContext(now, h.remoteId, h.localId, SignalSecurityScheme.ENCRYPTED_AND_SIGNED, transferId),
        )
        val result = h.handler.handle(
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.FILE,
                dispositionRequested = true,
                createdAt = now,
                expiresAt = now + 2.days,
                source = h.remoteId,
                target = h.localId,
                payload = envelope.encode(),
            ),
        )
        assertIs<InboundHandleResult.Success>(result)
        assertEquals(listOf(Triple(transferId, 3, emptyList())), h.coverageAcks.map { Triple(it.first, it.second, it.third) })
    }

    @Test
    fun complete_notifiesPusher() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val envelope = h.senderProtection.protect(
            FilePayload.Complete,
            EnvelopeProtectContext(now, h.remoteId, h.localId, SignalSecurityScheme.ENCRYPTED_AND_SIGNED, transferId),
        )
        val result = h.handler.handle(
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.FILE,
                dispositionRequested = true,
                createdAt = now,
                expiresAt = now + 2.days,
                source = h.remoteId,
                target = h.localId,
                payload = envelope.encode(),
            ),
        )
        assertIs<InboundHandleResult.Success>(result)
        assertEquals(listOf(transferId), h.completed)
    }

    @Test
    fun cancel_knownTransfer_dropsRowSilently() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        h.handler.handle(h.offerEnvelope(transferId))
        assertTrue(h.incoming.find(transferId) != null)
        val envelope = h.senderProtection.protect(
            FilePayload.Cancel(FileCancelReason.ABORT, reasonText = null),
            EnvelopeProtectContext(now, h.remoteId, h.localId, SignalSecurityScheme.ENCRYPTED_AND_SIGNED, transferId),
        )
        h.handler.handle(
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.FILE,
                dispositionRequested = true,
                createdAt = now,
                expiresAt = now + 2.days,
                source = h.remoteId,
                target = h.localId,
                payload = envelope.encode(),
            ),
        )
        assertTrue(h.incoming.find(transferId) == null)
        assertTrue(h.cancelled.isEmpty(), "receiver-side abort needs no pusher call")
    }

    @Test
    fun cancel_unknownTransfer_notifiesPusher() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val envelope = h.senderProtection.protect(
            FilePayload.Cancel(FileCancelReason.ABORT, reasonText = null),
            EnvelopeProtectContext(now, h.remoteId, h.localId, SignalSecurityScheme.ENCRYPTED_AND_SIGNED, transferId),
        )
        val result = h.handler.handle(
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.FILE,
                dispositionRequested = true,
                createdAt = now,
                expiresAt = now + 2.days,
                source = h.remoteId,
                target = h.localId,
                payload = envelope.encode(),
            ),
        )
        assertIs<InboundHandleResult.Success>(result)
        assertEquals(listOf(transferId), h.cancelled)
    }

    @Test
    fun request_isDeclinedWithFileId() = runTest {
        val h = harness()
        // protect() seals the REQUEST body inline (kind byte clear + sealed fields).
        val request = FilePayload.Request(fileId, 0L, emptyList())
        val envelope = h.senderProtection.protect(
            request,
            EnvelopeProtectContext(
                now,
                h.remoteId,
                h.localId,
                SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
                TransferId(Uuid.random()),
            ),
        )
        val env = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = h.remoteId,
            target = h.localId,
            payload = envelope.encode(),
        )
        val result = h.handler.handle(env)
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        // A declined REQUEST is normal traffic (the requester moves on), not a failure:
        // nothing is emitted and no serve starts.
        assertTrue(h.carrier.events.none { it is FileEvent.Failed && it.fileId == fileId })
    }
}
