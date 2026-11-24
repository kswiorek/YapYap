package org.yapyap.routing.file.inbound

import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.FileId
import org.yapyap.protocol.MIN_FILE_CHUNK_SIZE_BYTES
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileEnvelopeKind
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.FileSeal
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.TransferShape
import org.yapyap.routing.file.FileInboundHarness
import org.yapyap.routing.file.ScriptedFilePorts
import org.yapyap.routing.file.ScriptedPlaintext
import org.yapyap.routing.file.fileInboundHarness
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.store.FileSealKind
import org.yapyap.routing.store.FileSealStatus
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.OutgoingSeal
import org.yapyap.routing.store.OutgoingTransfer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * FILE inbound dispatch (docs/envelope-redesign.md §5): OFFER admission, CHUNK checks and their
 * sinks, ACK/CANCEL/COMPLETE routing, and REQUEST refusal — all over the real target side,
 * engine and seal registry, with passthrough protections and in-memory stores.
 */
class FileInboundHandlerTest {

    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val chunkSize = 220 * 1024
    private val fileTotal = 1000L

    private fun harness(ports: ScriptedFilePorts = ScriptedFilePorts()) = fileInboundHarness(
        ports = ports,
        plaintext = ScriptedPlaintext(describeImpl = { id ->
            if (id == fileId) {
                org.yapyap.routing.file.port.FileManifest(
                    fileId = fileId,
                    totalBytes = fileTotal,
                    blockSize = 1024 * 1024,
                    blockHashes = listOf(ByteArray(32) { 1 }),
                    fileName = "photo.jpg",
                    mimeType = "image/jpeg",
                )
            } else null
        }),
    )

    private fun seal(
        totalBytes: Long = fileTotal,
        key: SealKey = SealKey(ByteArray(32) { it.toByte() }),
    ) = FileSeal(
        fileId = fileId,
        totalBytes = totalBytes,
        blockSize = 1024 * 1024,
        blockHashes = listOf(ByteArray(32) { 1 }),
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        key = key,
    )

    private fun envelope(
        h: FileInboundHarness,
        header: FileHeader? = null,
        content: ByteArray = byteArrayOf(),
        source: PeerId = h.remoteId,
        rawHeader: ByteArray? = null,
    ): BinaryEnvelope = BinaryEnvelope(
        packetId = Uuid.random(),
        packetType = PacketType.FILE,
        dispositionRequested = true,
        createdAt = h.clock.now(),
        expiresAt = h.clock.now() + 2.days,
        source = source,
        target = h.localId,
        routingHeader = rawHeader ?: header!!.encode(),
        content = content,
        signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
    )

    private suspend fun offerEnvelope(
        h: FileInboundHarness,
        transferId: TransferId = TransferId(Uuid.random()),
        shape: TransferShape = TransferShape(chunkSize, 0, fileTotal),
        sealKey: SealKey = SealKey(ByteArray(32) { it.toByte() }),
        totalBytes: Long = fileTotal,
    ): BinaryEnvelope {
        val sealBytes = h.fileProtection.seal(seal(totalBytes, sealKey), h.localId)
        return envelope(
            h,
            FileHeader.Offer(transferId, origin = h.remoteId, finalTarget = h.localId, shape = shape),
            sealBytes,
        )
    }

    private suspend fun chunkEnvelope(
        h: FileInboundHarness,
        transferId: TransferId,
        index: Int,
        shape: TransferShape = TransferShape(chunkSize, 0, fileTotal),
        sealKey: SealKey = SealKey(ByteArray(32) { it.toByte() }),
        plaintext: ByteArray = ByteArray(shape.chunkLength(index)) { (it % 251).toByte() },
        source: PeerId = h.remoteId,
    ): BinaryEnvelope {
        val sealId =
            h.hopProtection.contentId(h.fileProtection.seal(seal(totalBytes = shape.byteTo, key = sealKey), h.localId))
        val ciphertext = h.fileProtection.encryptChunk(sealId, sealKey, shape.chunkOffset(index), plaintext)
        return envelope(h, FileHeader.Chunk(transferId, index), ciphertext, source = source)
    }

    private suspend fun sealIdOf(
        h: FileInboundHarness,
        totalBytes: Long = fileTotal,
        key: SealKey = SealKey(ByteArray(32) { it.toByte() }),
    ) = h.hopProtection.contentId(h.fileProtection.seal(seal(totalBytes, key), h.localId))

    @Test
    fun offer_accepted_createsRowAndAcks() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val result = h.handler.handle(offerEnvelope(h, transferId))
        assertIs<InboundHandleResult.Success>(result)
        val row = assertNotNull(h.incomingStore.findTransfer(transferId), "incoming-transfer row must exist")
        assertEquals(h.remoteId, row.sender)
        assertEquals(chunkSize, row.shape.chunkSizeBytes)
        val offer = h.ports.offers.single()
        assertEquals(fileId, offer.fileId)
        assertEquals("photo.jpg", offer.fileName)
        assertEquals(fileTotal, offer.totalBytes)
    }

    @Test
    fun offer_forReadyFile_ackedWithCompleteAndNoRow() = runTest {
        val h = harness(ScriptedFilePorts(offerVerdict = FileOfferVerdict.AlreadyReady))
        val transferId = TransferId(Uuid.random())
        val result = h.handler.handle(offerEnvelope(h, transferId))
        val success = assertIs<InboundHandleResult.Success>(result)
        assertNull(h.incomingStore.findTransfer(transferId), "READY file gets no row")
        val replies = success.sideEffects.filterIsInstance<InboundSideEffect.EnqueueDirect>()
        assertEquals(2, replies.size, "one ACK for the sender and one COMPLETE for the origin")
        val headers = replies.map { FileHeader.decode(it.envelope.routingHeader) }
        val ack = headers.filterIsInstance<FileHeader.Ack>().single()
        assertEquals(transferId, ack.transferId)
        assertEquals(-1, ack.highestContiguousIndex, "nothing was ours to skip")
        val complete = headers.filterIsInstance<FileHeader.Complete>().single()
        assertContentEquals(sealIdOf(h).bytes, complete.sealId.bytes)
    }

    @Test
    fun offer_refused_isDeclinedWithNoRow() = runTest {
        val h = harness(ScriptedFilePorts(offerVerdict = FileOfferVerdict.Refuse(FileRefusalReason.NO_SPACE)))
        val result = h.handler.handle(offerEnvelope(h))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
    }

    @Test
    fun offer_deferred_isSilent() = runTest {
        val h = harness(ScriptedFilePorts(offerVerdict = FileOfferVerdict.Defer))
        val result = h.handler.handle(offerEnvelope(h))
        assertIs<InboundHandleResult.Deferred>(result)
    }

    @Test
    fun chunk_beforeOffer_isNeverAcked() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val result = h.handler.handle(chunkEnvelope(h, transferId, 0))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertTrue(h.ports.ingests.isEmpty(), "unknown transferId: never stored, never ACKed")
    }

    @Test
    fun chunk_unknownTransferId_isDeclined() = runTest {
        val h = harness()
        h.handler.handle(offerEnvelope(h, TransferId(Uuid.random())))
        val result = h.handler.handle(chunkEnvelope(h, TransferId(Uuid.random()), 0))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertEquals(1, h.ports.offers.size)
        assertTrue(h.ports.ingests.isEmpty())
    }

    @Test
    fun chunk_staged_emitsProgressAndAcks() = runTest {
        val bigTotal = 1024L * 1024
        val h = harness(
            ScriptedFilePorts(
                ingestResult = FileIngestResult.Staged(
                    contiguousBytes = 440L * 1024,
                    totalBytes = bigTotal
                )
            )
        )
        val transferId = TransferId(Uuid.random())
        val shape = TransferShape(chunkSize, 0, bigTotal)
        h.handler.handle(offerEnvelope(h, transferId, shape = shape, totalBytes = bigTotal))
        val plaintext = ByteArray(shape.chunkLength(0)) { 3 }
        val result = h.handler.handle(chunkEnvelope(h, transferId, 0, shape = shape, plaintext = plaintext))
        val success = assertIs<InboundHandleResult.Success>(result)
        val (id, offset, bytes) = h.ports.ingests.single()
        assertEquals(fileId, id)
        assertEquals(0L, offset)
        assertContentEquals(plaintext, bytes)
        val ackReply = success.sideEffects.filterIsInstance<InboundSideEffect.EnqueueDirect>().single()
        assertEquals(h.remoteId, ackReply.envelope.target)
        val ack = assertIs<FileHeader.Ack>(FileHeader.decode(ackReply.envelope.routingHeader))
        assertEquals(transferId, ack.transferId)
        assertEquals(1, ack.highestContiguousIndex, "440 KiB of 220 KiB chunks covers indices 0..1")
    }

    @Test
    fun chunk_duplicate_isAcked() = runTest {
        val h = harness(ScriptedFilePorts(ingestResult = FileIngestResult.Duplicate(0L, 0L)))
        val transferId = TransferId(Uuid.random())
        h.handler.handle(offerEnvelope(h, transferId))
        val result = h.handler.handle(chunkEnvelope(h, transferId, 0))
        val success = assertIs<InboundHandleResult.Success>(result)
        val ackReply = success.sideEffects.filterIsInstance<InboundSideEffect.EnqueueDirect>().single()
        assertIs<FileHeader.Ack>(FileHeader.decode(ackReply.envelope.routingHeader))
    }

    @Test
    fun chunk_outOfRange_isDeclined() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        h.handler.handle(offerEnvelope(h, transferId))
        // A 1000-byte file in 220 KiB chunks has exactly one chunk: index 7 is outside the
        // transfer's shape and is DECLINED before any decrypt is burned.
        val result = h.handler.handle(chunkEnvelope(h, transferId, 7))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertTrue(h.ports.ingests.isEmpty())
    }

    @Test
    fun chunk_chunkOfAnotherSender_isDeclined() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        h.handler.handle(offerEnvelope(h, transferId))
        val stranger = PeerId("fi-stranger-cccccccccccccccccccccccccccccccccccccccccccccccc")
        val bad = chunkEnvelope(h, transferId, 0, source = stranger)
        val rejected = assertIs<InboundHandleResult.Rejected>(h.handler.handle(bad))
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertTrue(h.ports.ingests.isEmpty())
    }

    @Test
    fun chunk_wrongLength_isDeclined() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        h.handler.handle(offerEnvelope(h, transferId))
        val short = byteArrayOf(1, 2, 3)
        val bad = chunkEnvelope(h, transferId, 0, plaintext = short)
        val rejected = assertIs<InboundHandleResult.Rejected>(h.handler.handle(bad))
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertTrue(h.ports.ingests.isEmpty(), "an off-size chunk never reaches the ingest port")
    }

    @Test
    fun cancel_fromNonAuthor_isIgnored() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        h.handler.handle(offerEnvelope(h, transferId))
        assertEquals(1, h.ports.offers.size)
        val stranger = PeerId("fi-stranger-cccccccccccccccccccccccccccccccccccccccccccccccc")
        val result = h.handler.handle(
            envelope(h, FileHeader.Cancel(transferId, FileCancelReason.ABORT), source = stranger),
        )
        assertIs<InboundHandleResult.Success>(result)
        assertNotNull(h.incomingStore.findTransfer(transferId), "a stranger's CANCEL leaves the sender's row in place")
    }

    @Test
    fun chunk_deferred_isSilent() = runTest {
        val h = harness(ScriptedFilePorts(ingestResult = FileIngestResult.Defer))
        val transferId = TransferId(Uuid.random())
        h.handler.handle(offerEnvelope(h, transferId))
        val result = h.handler.handle(chunkEnvelope(h, transferId, 0))
        assertIs<InboundHandleResult.Deferred>(result)
    }

    @Test
    fun chunk_refused_isDeclined() = runTest {
        val h = harness(ScriptedFilePorts(ingestResult = FileIngestResult.Refused))
        val transferId = TransferId(Uuid.random())
        h.handler.handle(offerEnvelope(h, transferId))
        val result = h.handler.handle(chunkEnvelope(h, transferId, 0))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
    }

    @Test
    fun chunk_corruptHeader_isRejectedAsDecodeFailed() = runTest {
        val h = harness()
        val bad = envelope(h, rawHeader = byteArrayOf(0x7f, 0x00))
        val rejected = assertIs<InboundHandleResult.Rejected>(h.handler.handle(bad))
        assertEquals(PacketNackReason.DECODE_FAILED, rejected.reason)
        assertTrue(h.ports.ingests.isEmpty())
    }

    @Test
    fun chunk_expiredRow_isDeclined() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        h.handler.handle(offerEnvelope(h, transferId))
        h.clock.advanceTo(h.clock.now() + 3.days)
        val result = h.handler.handle(chunkEnvelope(h, transferId, 0))
        assertIs<InboundHandleResult.Rejected>(result)
        h.incomingStore.pruneExpired(h.clock.now())
        assertNull(h.incomingStore.findTransfer(transferId), "expired rows are swept")
    }

    @Test
    fun offer_resent_isIdempotent() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        assertIs<InboundHandleResult.Success>(h.handler.handle(offerEnvelope(h, transferId)))
        // The sender retries until our ACK lands: the same OFFER arrives again. One row,
        // ACKed again — never a second row for the same transferId.
        assertIs<InboundHandleResult.Success>(h.handler.handle(offerEnvelope(h, transferId)))
        assertEquals(1, h.ports.offers.size, "a re-sent OFFER is not checked twice")
        assertEquals(1, h.incomingStore.transfersByFile(fileId).size, "one incoming row per transferId")
    }

    @Test
    fun coverageAck_countsOnlyWholeChunks() {
        val chunk = 1000
        val shape = TransferShape(chunk, 0, 3_500L)
        assertEquals(-1, shape.highestContiguousIndex(0L), "nothing held")
        assertEquals(-1, shape.highestContiguousIndex(999L), "a partial first chunk is not covered")
        assertEquals(0, shape.highestContiguousIndex(1_000L), "exact boundary")
        assertEquals(1, shape.highestContiguousIndex(2_400L), "P mid-chunk covers only whole chunks")
        assertEquals(3, shape.highestContiguousIndex(3_500L), "P == total covers the short last chunk")
        assertEquals(-1, TransferShape(chunk, 0, 0).highestContiguousIndex(0L), "an empty file has no chunks")
    }

    @Test
    fun transferShape_acceptance() {
        val c = MIN_FILE_CHUNK_SIZE_BYTES
        val total = 3L * c + c / 2
        assertTrue(TransferShape(c, 0, total).isAcceptable())
        assertTrue(TransferShape(c, total, total).isAcceptable(), "an empty range is acceptable")
        assertTrue(TransferShape(c, 0, 0).isAcceptable(), "an empty file is valid: it has no chunks")
        assertFalse(TransferShape(c - 1, 0, total).isAcceptable(), "chunks below the minimum")
        assertFalse(TransferShape(c, 0, SMALL_FILE_MAX_BYTES + 1).isAcceptable(), "above the small-file limit")
    }

    @Test
    fun offer_zeroChunkSize_isRejectedAsDecodeFailed() = runTest {
        val h = harness()
        // A zero chunk size cannot be constructed as a TransferShape, so it is encoded by hand:
        // the header decode rejects it before admission sees anything.
        val wire = ByteWriter(64).apply {
            writeByte(FileEnvelopeKind.OFFER.wireValue)
            writeUuid(Uuid.random())
            writePeerId(h.remoteId)
            writePeerId(h.localId)
            writeInt(0)
            writeLong(0L)
            writeLong(fileTotal)
        }.toByteArray()
        val result = h.handler.handle(envelope(h, rawHeader = wire))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECODE_FAILED, rejected.reason)
        assertTrue(h.ports.offers.isEmpty(), "a malformed OFFER is never stored or seal-opened")
    }

    @Test
    fun offer_rangeNotEndingAtFileEnd_isRejected() = runTest {
        val h = harness()
        // Transfers always run to the end of the file: a range ending early cannot be placed.
        val result = h.handler.handle(offerEnvelope(h, shape = TransferShape(chunkSize, 0, fileTotal / 2)))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertTrue(h.ports.offers.isEmpty(), "a malformed OFFER is never stored or seal-opened")
    }

    @Test
    fun ack_updatesOurOutgoingTransfer() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        val key = SealKey(ByteArray(32) { it.toByte() })
        val sealBytes = h.fileProtection.seal(seal(totalBytes = 4L * 1024, key = key), h.remoteId)
        val sealId = h.hopProtection.contentId(sealBytes)
        h.sealStore.insert(
            OutgoingSeal(
                sealId = sealId,
                fileId = fileId,
                targetDevice = h.remoteId,
                kind = FileSealKind.PUSH,
                sealBytes = sealBytes,
                key = key,
                totalBytes = 4L * 1024,
                status = FileSealStatus.SENDING,
                createdAt = h.clock.now(),
                expiresAt = h.clock.now() + 2.days,
            ),
        )
        val shape = TransferShape(1024, 0, 4L * 1024)
        h.transferStore.insert(
            OutgoingTransfer(
                transferId = transferId,
                sealId = sealId,
                relayTransferId = null,
                peer = h.remoteId,
                shape = shape,
                nextChunkIndex = 0,
                contiguousAcked = -1,
                offerPacketId = null,
                status = FileTransferStatus.OFFERED,
                createdAt = h.clock.now(),
                expiresAt = h.clock.now() + 2.days,
            ),
        )
        val result = h.handler.handle(
            envelope(
                h,
                FileHeader.Ack(transferId, highestContiguousIndex = 2, missingRanges = emptyList())
            )
        )
        assertIs<InboundHandleResult.Success>(result)
        assertEquals(2, h.transferStore.find(transferId)?.contiguousAcked)
    }

    @Test
    fun complete_notifiesSealOrigin() = runTest {
        val h = harness()
        val key = SealKey(ByteArray(32) { it.toByte() })
        val sealBytes = h.fileProtection.seal(seal(totalBytes = fileTotal, key = key), h.remoteId)
        val sealId = h.hopProtection.contentId(sealBytes)
        h.sealStore.insert(
            OutgoingSeal(
                sealId = sealId,
                fileId = fileId,
                targetDevice = h.remoteId,
                kind = FileSealKind.PUSH,
                sealBytes = sealBytes,
                key = key,
                totalBytes = fileTotal,
                status = FileSealStatus.SENDING,
                createdAt = h.clock.now(),
                expiresAt = h.clock.now() + 2.days,
            ),
        )
        val result = h.handler.handle(envelope(h, FileHeader.Complete(sealId)))
        assertIs<InboundHandleResult.Success>(result)
        assertEquals(FileSealStatus.DELIVERED, h.sealStore.find(sealId)?.status)
    }

    @Test
    fun cancel_knownTransfer_dropsRowSilently() = runTest {
        val h = harness()
        val transferId = TransferId(Uuid.random())
        h.handler.handle(offerEnvelope(h, transferId))
        assertNotNull(h.incomingStore.findTransfer(transferId))
        h.handler.handle(envelope(h, FileHeader.Cancel(transferId, FileCancelReason.ABORT)))
        assertNull(h.incomingStore.findTransfer(transferId))
    }

    @Test
    fun cancel_unknownTransfer_isANoOp() = runTest {
        val h = harness()
        val result = h.handler.handle(envelope(h, FileHeader.Cancel(TransferId(Uuid.random()), FileCancelReason.ABORT)))
        assertIs<InboundHandleResult.Success>(result)
    }

    @Test
    fun request_notHeld_isDeclined() = runTest {
        val h = harness()
        val requestSeal = FileHeader.Request
        val content = h.fileProtection.sealRequest(
            org.yapyap.protocol.envelopes.FileRequest(fileId, 0L, emptyList()),
            h.localId,
        )
        val result = h.handler.handle(envelope(h, requestSeal, content))
        val rejected = assertIs<InboundHandleResult.Rejected>(result)
        assertEquals(PacketNackReason.DECLINED, rejected.reason)
        assertTrue(h.sealStore.byFile(fileId).isEmpty(), "nothing held: no serve seal")
    }
}
