package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.FILE_BLOCK_MAX_COUNT
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileDeclineReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.protocol.LargeFileFrameKind
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.TransferId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

class FileEnvelopeCodecTest {

    private val source = PeerId("file-src")
    private val target = PeerId("file-dst")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    @Test
    fun fileEnvelopeKind_wireValuesDistinct() {
        val wires = FileEnvelopeKind.entries.map { it.wireValue }.toSet()
        assertEquals(FileEnvelopeKind.entries.size, wires.size)
    }

    @Test
    fun fileHeader_offer_encodeDecode_roundTrip() {
        val original = FileHeader.Offer(
            transferId = TransferId(Uuid.random()),
            origin = source,
            finalTarget = target,
            shape = TransferShape(chunkSizeBytes = 100 * 1024, byteFrom = 0L, byteTo = 999L),
        )
        val decoded = FileHeader.decode(original.encode()) as FileHeader.Offer
        assertEquals(original, decoded)
    }

    @Test
    fun fileHeader_chunk_encodeDecode_roundTrip() {
        val original = FileHeader.Chunk(TransferId(Uuid.random()), chunkIndex = 2)
        val decoded = FileHeader.decode(original.encode()) as FileHeader.Chunk
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.chunkIndex, decoded.chunkIndex)
    }

    @Test
    fun fileHeader_ack_encodeDecode_roundTrip() {
        val original = FileHeader.Ack(
            transferId = TransferId(Uuid.random()),
            highestContiguousIndex = 3,
            missingRanges = listOf(ChunkRange(5, 2), ChunkRange(9, 1)),
        )
        val decoded = FileHeader.decode(original.encode()) as FileHeader.Ack
        assertEquals(original, decoded)
    }

    @Test
    fun fileHeader_complete_encodeDecode_roundTrip() {
        val sealIdBytes = ByteArray(32) { it.toByte() }
        val original = FileHeader.Complete(org.yapyap.protocol.ContentId(sealIdBytes))
        val decoded = FileHeader.decode(original.encode()) as FileHeader.Complete
        assertEquals(original.sealId, decoded.sealId)
    }

    @Test
    fun fileHeader_request_encodeDecode_roundTrip() {
        val original = FileHeader.Request
        val decoded = FileHeader.decode(original.encode())
        assertTrue(decoded is FileHeader.Request)
    }

    @Test
    fun fileHeader_cancel_encodeDecode_roundTrip() {
        val original = FileHeader.Cancel(TransferId(Uuid.random()), reason = FileCancelReason.ABORT)
        val decoded = FileHeader.decode(original.encode()) as FileHeader.Cancel
        assertEquals(original.reason, decoded.reason)
        assertEquals(original.transferId, decoded.transferId)
    }

    @Test
    fun fileHeader_cancel_allReasons_roundTrip() {
        for (reason in FileCancelReason.entries) {
            val decoded =
                FileHeader.decode(FileHeader.Cancel(TransferId(Uuid.random()), reason).encode()) as FileHeader.Cancel
            assertEquals(reason, decoded.reason)
        }
    }

    @Test
    fun fileSeal_encodeDecode_roundTrip() {
        val original = FileSeal(
            fileId = fileId,
            totalBytes = 999L,
            blockSize = 1024 * 1024,
            blockHashes = listOf(ByteArray(32) { it.toByte() }),
            fileName = "doc.bin",
            mimeType = "application/octet-stream",
            key = SealKey(ByteArray(32) { 9 }),
        )
        val decoded = FileSeal.decode(original.encode())
        assertEquals(original.fileId, decoded.fileId)
        assertEquals(original.totalBytes, decoded.totalBytes)
        assertEquals(original.blockSize, decoded.blockSize)
        assertEquals(original.fileName, decoded.fileName)
        assertEquals(original.mimeType, decoded.mimeType)
        assertEquals(original.key, decoded.key)
        assertEquals(original.blockHashes.size, decoded.blockHashes.size)
        original.blockHashes.zip(decoded.blockHashes).forEach { (a, b) -> assertContentEquals(a, b) }
    }

    @Test
    fun fileRequest_encodeDecode_roundTrip() {
        val original = FileRequest(
            fileId = fileId,
            contiguousBytes = 1234L,
            missingRanges = listOf(ByteRange(1234L, 100L)),
        )
        val decoded = FileRequest.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun transferShape_coverage_mapping() {
        val shape = TransferShape(chunkSizeBytes = 100 * 1024, byteFrom = 0L, byteTo = 250L * 1024L)
        assertEquals(3, shape.chunkCount)
        assertEquals(-1, shape.highestContiguousIndex(0L))
        assertEquals(0, shape.highestContiguousIndex(100L * 1024L))
        assertEquals(2, shape.highestContiguousIndex(250L * 1024L))
    }

    @Test
    fun fileId_isEncodedOnlyBySealedContent() {
        // The seal and the REQUEST carry the fileId; no public header field does.
        val request = FileRequest(fileId, 0L, emptyList()).encode()
        assertTrue(request.decodeToString().contains(fileId.value))
        val sealed = FileSeal(
            fileId = fileId,
            totalBytes = 10L,
            blockSize = 1024 * 1024,
            blockHashes = emptyList(),
            fileName = "a",
            mimeType = null,
            key = SealKey(ByteArray(32)),
        ).encode()
        assertTrue(sealed.decodeToString().contains(fileId.value))
        // Public headers never carry it.
        val offer =
            FileHeader.Offer(TransferId(Uuid.random()), source, target, TransferShape(100 * 1024, 0, 10)).encode()
        assertTrue(!offer.decodeToString().contains(fileId.value))
    }

    @Test
    fun largeFileFrame_pull_encodeDecode_roundTrip() {
        val original = LargeFileFrame.Pull(fileId = fileId, fromOffset = 12345L)
        val decoded = LargeFileFrame.Pull.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(original, LargeFileFrame.decode(original.encode()) as LargeFileFrame.Pull)
    }

    @Test
    fun largeFileFrame_pullOk_encodeDecode_roundTrip() {
        val original = LargeFileFrame.PullOk(
            totalBytes = 10L * 1024L * 1024L,
            blockSize = 1024 * 1024,
            blockHashes = listOf(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            fileName = "big.bin",
            mimeType = "application/octet-stream",
        )
        val decoded = LargeFileFrame.PullOk.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun largeFileFrame_pullOk_emptyFile_roundTrip() {
        val original = LargeFileFrame.PullOk(
            totalBytes = 0L,
            blockSize = 1024 * 1024,
            blockHashes = emptyList(),
            fileName = "empty",
            mimeType = null,
        )
        val decoded = LargeFileFrame.PullOk.decode(original.encode())
        assertEquals(0L, decoded.totalBytes)
        assertTrue(decoded.blockHashes.isEmpty())
    }

    @Test
    fun largeFileFrame_declined_encodeDecode_roundTrip() {
        val original = LargeFileFrame.Declined(reason = LargeFileDeclineReason.NOT_ALLOWED)
        val decoded = LargeFileFrame.Declined.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun largeFileFrame_progress_encodeDecode_roundTrip() {
        val original = LargeFileFrame.Progress(contiguousBytes = 8L * 1024L * 1024L)
        val decoded = LargeFileFrame.Progress.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun largeFileFrame_abort_encodeDecode_roundTrip() {
        val original = LargeFileFrame.Abort(reason = LargeFileAbortReason.STALLED, reasonText = "gap")
        val decoded = LargeFileFrame.Abort.decode(original.encode())
        assertEquals(original.reason, decoded.reason)
        assertEquals(original.reasonText, decoded.reasonText)
    }

    @Test
    fun largeFileFrame_abort_nullReasonText_roundTrip() {
        val original = LargeFileFrame.Abort(reason = LargeFileAbortReason.CANCELLED, reasonText = null)
        val decoded = LargeFileFrame.Abort.decode(original.encode())
        assertEquals(LargeFileAbortReason.CANCELLED, decoded.reason)
        assertNull(decoded.reasonText)
    }

    @Test
    fun largeFileFrame_data_encodeDecode_roundTrip() {
        val original = LargeFileFrame.Data(bytes = byteArrayOf(1, 2, 3, 4, 5))
        val decoded = LargeFileFrame.Data.decode(original.encode())
        assertEquals(original, decoded)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), decoded.bytes)
    }

    @Test
    fun largeFileFrame_data_empty_roundTrip() {
        val original = LargeFileFrame.Data(bytes = byteArrayOf())
        val decoded = LargeFileFrame.Data.decode(original.encode())
        assertContentEquals(byteArrayOf(), decoded.bytes)
    }

    @Test
    fun largeFileFrameKind_wireValuesDistinct() {
        val wires = LargeFileFrameKind.entries.map { it.wireValue }.toSet()
        assertEquals(LargeFileFrameKind.entries.size, wires.size)
    }

    @Test
    fun largeFileFrameKind_wireValues_pinned() {
        val kinds = LargeFileFrameKind.entries.associate { it.name to it.wireValue.toInt() }
        assertEquals(
            mapOf("PULL" to 21, "PULL_OK" to 22, "DECLINED" to 23, "PROGRESS" to 24, "ABORT" to 25, "DATA" to 26),
            kinds,
        )
        val declines = LargeFileDeclineReason.entries.associate { it.name to it.wireValue.toInt() }
        assertEquals(mapOf("NOT_HELD" to 1, "NOT_ALLOWED" to 2), declines)
        val aborts = LargeFileAbortReason.entries.associate { it.name to it.wireValue.toInt() }
        assertEquals(
            mapOf("SOURCE_ERROR" to 1, "NO_SPACE" to 2, "STALLED" to 3, "CANCELLED" to 4),
            aborts,
        )
    }

    @Test
    fun largeFileFrame_decode_dispatchesAllKinds() {
        val fetch = LargeFileFrame.Pull(fileId, 0L).encode()
        assertTrue(LargeFileFrame.decode(fetch) is LargeFileFrame.Pull)
        val ok = LargeFileFrame.PullOk(100L, 1024 * 1024, emptyList(), "a", null).encode()
        assertTrue(LargeFileFrame.decode(ok) is LargeFileFrame.PullOk)
        val declined = LargeFileFrame.Declined(LargeFileDeclineReason.NOT_HELD).encode()
        assertTrue(LargeFileFrame.decode(declined) is LargeFileFrame.Declined)
        val progress = LargeFileFrame.Progress(0L).encode()
        assertTrue(LargeFileFrame.decode(progress) is LargeFileFrame.Progress)
        val abort = LargeFileFrame.Abort(LargeFileAbortReason.NO_SPACE, null).encode()
        assertTrue(LargeFileFrame.decode(abort) is LargeFileFrame.Abort)
        val data = LargeFileFrame.Data(byteArrayOf(9)).encode()
        assertTrue(LargeFileFrame.decode(data) is LargeFileFrame.Data)
    }

    @Test
    fun largeFileFrame_framesCarryNoTransferId() {
        // Large-file frames are channel messages (type byte + body), not envelopes:
        // decoding them as FileHeader must fail, and vice versa.
        val fetch = LargeFileFrame.Pull(fileId, 0L).encode()
        assertFailsWith<IllegalStateException> {
            FileHeader.decode(fetch)
        }
    }

    @Test
    fun largeFileFrame_pullCarriesFileIdInClear_channelProtected() {
        // PULL carries the fileId in clear inside the DTLS channel (design §4,
        // invariant 23): only the peer device ever sees the channel. This is
        // expected and distinct from envelope kinds, which must never carry it.
        val fetch = LargeFileFrame.Pull(fileId = fileId, fromOffset = 0L).encode()
        assertTrue(fetch.decodeToString().contains(fileId.value.take(8)))
    }

    // Each hostile input below carries only the oversized prefix, nothing after it. A decoder
    // that trusted the prefix would either try to allocate it (an OutOfMemoryError for a count
    // of Int.MAX_VALUE) or run off the end ("Unexpected end of envelope"); the bounded decoder
    // rejects the prefix itself, which is what the message checks prove.

    private fun assertRejectedByBound(expected: String, decode: () -> Unit) {
        val e = assertFailsWith<IllegalArgumentException> { decode() }
        assertTrue(e.message.orEmpty().contains(expected), "rejected by its bound, not later: ${e.message}")
    }

    private fun sealPrefix(): ByteWriter = ByteWriter(128).apply {
        writeString(fileId.value)
        writeLong(100L)
        writeInt(1024 * 1024)
    }

    private fun pullOkPrefix(): ByteWriter = ByteWriter(128).apply {
        writeByte(LargeFileFrameKind.PULL_OK.wireValue)
        writeLong(100L)
        writeInt(1024 * 1024)
    }

    @Test
    fun request_hugeRangeCount_rejectedBeforeAllocating() {
        val request = FileRequest(fileId, 0L, emptyList()).encode()
        // Corrupt the range count: fileId string + contiguous(8) then count. Rebuild manually.
        val wire = ByteWriter(80).apply {
            writeString(fileId.value)
            writeLong(0L)
            writeInt(Int.MAX_VALUE)
        }.toByteArray()
        assertRejectedByBound("out of bounds") { FileRequest.decode(wire) }
    }

    @Test
    fun ack_hugeRangeCount_rejectedBeforeAllocating() {
        val wire = ByteWriter(16).apply {
            writeByte(FileEnvelopeKind.ACK.wireValue)
            writeUuid(Uuid.random())
            writeInt(0)
            writeInt(Int.MAX_VALUE)
        }.toByteArray()
        assertRejectedByBound("out of bounds") { FileHeader.decode(wire) }
    }

    @Test
    fun seal_hugeBlockCount_rejectedBeforeAllocating() {
        val wire = sealPrefix().apply { writeInt(Int.MAX_VALUE) }.toByteArray()
        assertRejectedByBound("out of bounds") { FileSeal.decode(wire) }
    }

    @Test
    fun seal_oneOverMaxBlockCount_isRejected() {
        val wire = sealPrefix().apply { writeInt(FILE_BLOCK_MAX_COUNT + 1) }.toByteArray()
        assertRejectedByBound("out of bounds") { FileSeal.decode(wire) }
    }

    @Test
    fun seal_hugeHashLength_rejectedBeforeAllocating() {
        val wire = sealPrefix().apply {
            writeInt(1)
            writeInt(Int.MAX_VALUE)
        }.toByteArray()
        assertRejectedByBound("exceeds max 32") { FileSeal.decode(wire) }
    }

    @Test
    fun seal_shortHash_isRejected() {
        val wire = sealPrefix().apply {
            writeInt(1)
            writeByteArray(ByteArray(31) { 1 })
            writeByteArray(ByteArray(32) { 2 })
            writeString("a")
            writeNullableString(null)
        }.toByteArray()
        // Short hash is caught by the exact-length check; message wording may differ, accept either.
        try {
            FileSeal.decode(wire)
            fail("expected rejection of short hash")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun seal_longFileName_rejectedBeforeAllocating() {
        val wire = sealPrefix().apply {
            writeInt(0)
            writeShort(0xfffe)
        }.toByteArray()
        assertRejectedByBound("exceeds max 255") { FileSeal.decode(wire) }
    }

    @Test
    fun pullOk_hugeBlockCount_rejectedBeforeAllocating() {
        val wire = pullOkPrefix().apply { writeInt(Int.MAX_VALUE) }.toByteArray()
        assertRejectedByBound("out of bounds") { LargeFileFrame.PullOk.decode(wire) }
    }

    @Test
    fun pullOk_shortHash_isRejected() {
        val wire = pullOkPrefix().apply {
            writeInt(1)
            writeByteArray(ByteArray(31) { 4 })
            writeString("a")
            writeNullableString(null)
        }.toByteArray()
        assertRejectedByBound("exactly 32 bytes") { LargeFileFrame.PullOk.decode(wire) }
    }

    @Test
    fun pullOk_longFileName_rejectedBeforeAllocating() {
        val wire = pullOkPrefix().apply {
            writeInt(0)
            writeShort(0xfffe)
        }.toByteArray()
        assertRejectedByBound("exceeds max 255") { LargeFileFrame.PullOk.decode(wire) }
    }

    @Test
    fun abort_longReasonText_rejectedBeforeAllocating() {
        val wire = ByteWriter(8).apply {
            writeByte(LargeFileFrameKind.ABORT.wireValue)
            writeByte(LargeFileAbortReason.STALLED.wireValue)
            writeShort(0xfffe)
        }.toByteArray()
        assertRejectedByBound("exceeds max") { LargeFileFrame.Abort.decode(wire) }
    }
}
