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
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.ChunkRange
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FileEnvelopeKind
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FileIncomingTransfers
import org.yapyap.routing.file.FileTransferSignals
import org.yapyap.routing.file.isValidOfferPublicHalf
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.router.FakeFilePorts
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryFileIncomingTransferStore
import org.yapyap.routing.router.InMemoryFileRelayStore
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.NoopFilePushOps
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.store.FileIncomingTransferStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

internal class ScriptedFileIngestPort(
    var offerVerdict: FileOfferVerdict = FileOfferVerdict.Accept,
    var ingestResult: FileIngestResult = FileIngestResult.Staged(0, 0),
) : FakeFilePorts {
    val offers = mutableListOf<FileManifest>()
    val ingests = mutableListOf<Triple<FileId, Long, ByteArray>>()

    override suspend fun checkOffer(offer: FileManifest): FileOfferVerdict {
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

    override suspend fun requestTotalBytes(fileId: FileId): Long? = null
}

class FileInboundHandlerTest {

    private val crypto = DefaultCryptoProvider()
    private val now = epochSeconds(10_000L)
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    private data class Harness(
        val handler: InboundEnvelopeHandler,
        val target: FileTargetHandler,
        val senderProtection: SignedAndEncryptedFileProtection,
        val incoming: FileIncomingTransferStore,
        val ingest: ScriptedFileIngestPort,
        val localId: PeerId,
        val remoteId: PeerId,
        val senderSigningKey: org.yapyap.crypto.keys.SigningPrivateKey,
        val clock: FakeClock,
        val coverageAcks: MutableList<Triple<TransferId, Int, List<ChunkRange>>>,
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
        val transfers = FileIncomingTransfers(incoming, clock)
        val coverageAcks = mutableListOf<Triple<TransferId, Int, List<ChunkRange>>>()
        val completed = mutableListOf<TransferId>()
        val cancelled = mutableListOf<TransferId>()
        val signals = object : FileTransferSignals {
            override suspend fun coverageAck(
                transferId: TransferId,
                from: PeerId,
                contiguousChunks: Int,
                missing: List<ChunkRange>,
            ) {
                coverageAcks += Triple(transferId, contiguousChunks, missing)
            }

            override suspend fun complete(transferId: TransferId, from: PeerId) {
                completed += transferId
            }

            override suspend fun cancelled(
                transferId: TransferId,
                from: PeerId,
                reason: FileCancelReason,
            ) {
                cancelled += transferId
            }
        }
        val target = FileTargetHandler(ctx, transfers, ingest, signals, onOfferReceived = {})
        val door = FileRelayDoor(ctx, InMemoryFileRelayStore(), TrackingPacketOutbox(), onRelayWhole = {})
        val serve = FileServeHandler(ctx, ingest, NoopFilePushOps())
        return Harness(
            handler = FileInboundHandler(ctx, target, door, serve),
            target = target,
            senderProtection = senderProtection,
            incoming = incoming,
            ingest = ingest,
            localId = local.deviceId,
            remoteId = senderPeer.device.deviceId,
            senderSigningKey = senderPeer.signingPrivateKey,
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
                ingestResult = FileIngestResult.Staged(contiguousBytes = 440L * 1024, totalBytes = 1024L * 1024),
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
        val h = harness(ingest = ScriptedFileIngestPort(ingestResult = FileIngestResult.Duplicate(0L, 0L)))
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        h.handler.handle(h.offerEnvelope(transferId))
        // Chunk 0 of the offered 1-chunk file: covered, so the ingest reports a duplicate
        // and the target answers with a coverage ACK instead of nothing useful.
        val result = h.handler.handle(h.chunkEnvelope(transferId, 0, key))
        val success = assertIs<InboundHandleResult.Success>(result)
        val ackReply = success.sideEffects.filterIsInstance<InboundSideEffect.EnqueueDirect>().single()
        assertIs<FilePayload.Ack>(FileEnvelope.decode(ackReply.envelope.payload).decodePayload())
    }

    @Test
    fun chunk_outOfRange_isDeclined() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        h.handler.handle(h.offerEnvelope(transferId, totalBytes = 1000L))
        // A 1000-byte file in 220 KiB chunks has exactly one chunk: index 7 is outside
        // the transfer's covered suffix and is DECLINED before any decrypt is burned.
        val result = h.handler.handle(h.chunkEnvelope(transferId, 7, key))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertTrue(h.ingest.ingests.isEmpty())
    }

    @Test
    fun cancel_fromNonAuthor_isIgnored() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        h.handler.handle(h.offerEnvelope(transferId))
        assertEquals(1, h.ingest.offers.size)

        // A CANCEL for a known transfer from anyone but its author is ignored (still
        // ACKed): a third party can never kill someone else's download.
        val stranger = FileEnvelope(
            transferId = transferId,
            source = PeerId("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            target = h.localId,
            createdAt = h.clock.now(),
            nonce = ByteArray(24),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = null,
            payload = FilePayload.Cancel(FileCancelReason.ABORT, null).encode(),
        )
        val result = h.target.handleCancel(stranger, FilePayload.Cancel(FileCancelReason.ABORT, null))
        assertIs<InboundHandleResult.Success>(result)
        assertTrue(h.cancelled.isEmpty(), "no cancel signal for a foreign CANCEL")
        assertNotNull(h.incoming.find(transferId), "a stranger's CANCEL leaves the author's row in place")
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
    fun offer_resent_isIdempotent() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val key = ByteArray(32) { it.toByte() }
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.offerEnvelope(transferId, key = key)))
        // The sender retries until our ACK lands: the same OFFER arrives again. One row,
        // ACKed again — never a second row for the same transferId.
        assertIs<InboundHandleResult.Success>(h.handler.handle(h.offerEnvelope(transferId, key = key)))
        assertEquals(1, h.ingest.offers.size, "a re-sent OFFER is not checked twice")
        assertEquals(1, h.incoming.findByFile(fileId).size, "one incoming row per transferId")
    }

    @Test
    fun coverageAck_countsOnlyWholeChunks() {
        val chunk = 1000
        val total = 3_500L
        assertEquals(-1, FileTargetHandler.coverageAckFor(0L, total, chunk), "nothing held")
        assertEquals(-1, FileTargetHandler.coverageAckFor(999L, total, chunk), "a partial first chunk is not covered")
        assertEquals(0, FileTargetHandler.coverageAckFor(1_000L, total, chunk), "exact boundary")
        assertEquals(1, FileTargetHandler.coverageAckFor(2_400L, total, chunk), "P mid-chunk covers only whole chunks")
        assertEquals(3, FileTargetHandler.coverageAckFor(total, total, chunk), "P == total covers the short last chunk")
        assertEquals(-1, FileTargetHandler.coverageAckFor(0L, 0L, chunk), "an empty file has no chunks")
    }

    @Test
    fun offerPublicHalf_validation() {
        fun offer(chunk: Int, total: Long, start: Int) =
            FilePayload.Offer(
                chunkSizeBytes = chunk,
                totalBytes = total,
                startIndex = start,
                sealedOfferBytes = ByteArray(1)
            )
        assertTrue(isValidOfferPublicHalf(offer(1000, 3_500L, 0)))
        assertTrue(isValidOfferPublicHalf(offer(1000, 3_500L, 4)), "start may equal the chunk count (nothing left)")
        assertTrue(isValidOfferPublicHalf(offer(1000, 0L, 0)), "an empty file is valid: it has no chunks")
        // Zero chunk size and negative size/start cannot be constructed (the Offer type
        // refuses them), so only the range and size checks are reachable here.
        assertFalse(isValidOfferPublicHalf(offer(1000, 3_500L, 5)), "start past the chunk count")
        assertFalse(isValidOfferPublicHalf(offer(1000, SMALL_FILE_MAX_BYTES + 1, 0)), "above the small-file limit")
    }

    @Test
    fun offer_zeroChunkSize_isRejected() = runTest {
        val h = harness()
        // Malformed public half, encoded by hand: the Offer codec requires chunkSizeBytes > 0,
        // so a zero chunk size never reaches the handler's own validation — protection maps the
        // undecodable payload to a permanent rejection and nothing is stored or sealed-opened.
        val sealed = h.senderProtection.sealOffer(
            FilePayload.SealedOffer(
                fileId = fileId,
                blockSize = 1024 * 1024,
                blockHashes = listOf(ByteArray(32) { 1 }),
                transferKey = ByteArray(32) { it.toByte() },
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
            ),
            h.localId,
        )
        val writer = ByteWriter(64 + sealed.size)
        writer.writeByte(FileEnvelopeKind.OFFER.wireValue)
        writer.writeInt(0)
        writer.writeLong(1000L)
        writer.writeInt(0)
        writer.writeByteArray(sealed)
        val transferId = TransferId(Uuid.random())
        val unsigned = FileEnvelope(
            transferId = transferId,
            source = h.remoteId,
            target = h.localId,
            createdAt = h.clock.now(),
            nonce = ByteArray(24),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = null,
            payload = writer.toByteArray(),
        )
        val signature = crypto.signDetached(h.senderSigningKey, unsigned.encodeForSigning())
        val result = h.handler.handle(
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.FILE,
                dispositionRequested = true,
                createdAt = h.clock.now(),
                expiresAt = h.clock.now() + 2.days,
                source = h.remoteId,
                target = h.localId,
                payload = unsigned.copy(signature = signature).encode(),
            ),
        )
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECODE_FAILED, rejected.reason)
        assertTrue(h.ingest.offers.isEmpty(), "a malformed OFFER is never stored or sealed-opened")
    }

    @Test
    fun offer_outOfRangeStartIndex_isRejected() = runTest {
        val h = harness()
        // The public half is validated exactly as the relay door does (design §3.4):
        // startIndex past the covered suffix is never stored or sealed-opened.
        // (chunkSizeBytes <= 0 never reaches the handler: the Offer codec requires > 0.)
        val result = h.handler.handle(h.offerEnvelope(totalBytes = 1000L, startIndex = 5))
        assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, result.reason)
        assertTrue(h.ingest.offers.isEmpty(), "a malformed OFFER is never stored or sealed-opened")
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
    }
}
