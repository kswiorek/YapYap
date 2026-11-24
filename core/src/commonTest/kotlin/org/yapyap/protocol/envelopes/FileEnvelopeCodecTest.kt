package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.FILE_BLOCK_MAX_COUNT
import org.yapyap.protocol.FileFetchAbortReason
import org.yapyap.protocol.FileFetchDeclineReason
import org.yapyap.protocol.FileFetchFrame
import org.yapyap.protocol.FileFetchKind
import org.yapyap.protocol.FileId
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
    fun fileFetch_fetch_encodeDecode_roundTrip() {
        val original = FileFetchFrame.Fetch(fileId = fileId, fromOffset = 12345L)
        val decoded = FileFetchFrame.Fetch.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(original, FileFetchFrame.decode(original.encode()) as FileFetchFrame.Fetch)
    }

    @Test
    fun fileFetch_fetchOk_encodeDecode_roundTrip() {
        val original = FileFetchFrame.FetchOk(
            totalBytes = 10L * 1024L * 1024L,
            blockSize = 1024 * 1024,
            blockHashes = listOf(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            fileName = "big.bin",
            mimeType = "application/octet-stream",
        )
        val decoded = FileFetchFrame.FetchOk.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun fileFetch_fetchOk_emptyFile_roundTrip() {
        val original = FileFetchFrame.FetchOk(
            totalBytes = 0L,
            blockSize = 1024 * 1024,
            blockHashes = emptyList(),
            fileName = "empty",
            mimeType = null,
        )
        val decoded = FileFetchFrame.FetchOk.decode(original.encode()) as FileFetchFrame.FetchOk
        assertEquals(0L, decoded.totalBytes)
        assertTrue(decoded.blockHashes.isEmpty())
    }

    @Test
    fun fileFetch_declined_encodeDecode_roundTrip() {
        val original = FileFetchFrame.FetchDeclined(reason = FileFetchDeclineReason.NOT_ALLOWED)
        val decoded = FileFetchFrame.FetchDeclined.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun fileFetch_progress_encodeDecode_roundTrip() {
        val original = FileFetchFrame.Progress(contiguousBytes = 8L * 1024L * 1024L)
        val decoded = FileFetchFrame.Progress.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun fileFetch_abort_encodeDecode_roundTrip() {
        val original = FileFetchFrame.Abort(reason = FileFetchAbortReason.STALLED, reasonText = "gap")
        val decoded = FileFetchFrame.Abort.decode(original.encode())
        assertEquals(original.reason, decoded.reason)
        assertEquals(original.reasonText, decoded.reasonText)
    }

    @Test
    fun fileFetch_abort_nullReasonText_roundTrip() {
        val original = FileFetchFrame.Abort(reason = FileFetchAbortReason.CANCELLED, reasonText = null)
        val decoded = FileFetchFrame.Abort.decode(original.encode())
        assertEquals(FileFetchAbortReason.CANCELLED, decoded.reason)
        assertNull(decoded.reasonText)
    }

    @Test
    fun fileFetch_data_encodeDecode_roundTrip() {
        val original = FileFetchFrame.Data(bytes = byteArrayOf(1, 2, 3, 4, 5))
        val decoded = FileFetchFrame.Data.decode(original.encode())
        assertEquals(original, decoded)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), decoded.bytes)
    }

    @Test
    fun fileFetch_data_empty_roundTrip() {
        val original = FileFetchFrame.Data(bytes = byteArrayOf())
        val decoded = FileFetchFrame.Data.decode(original.encode())
        assertContentEquals(byteArrayOf(), decoded.bytes)
    }

    @Test
    fun fileFetchKind_wireValuesDistinct() {
        val wires = FileFetchKind.entries.map { it.wireValue }.toSet()
        assertEquals(FileFetchKind.entries.size, wires.size)
    }

    @Test
    fun fileFetch_decode_dispatchesAllKinds() {
        val fetch = FileFetchFrame.Fetch(fileId, 0L).encode()
        assertTrue(FileFetchFrame.decode(fetch) is FileFetchFrame.Fetch)
        val ok = FileFetchFrame.FetchOk(100L, 1024 * 1024, emptyList(), "a", null).encode()
        assertTrue(FileFetchFrame.decode(ok) is FileFetchFrame.FetchOk)
        val declined = FileFetchFrame.FetchDeclined(FileFetchDeclineReason.NOT_HELD).encode()
        assertTrue(FileFetchFrame.decode(declined) is FileFetchFrame.FetchDeclined)
        val progress = FileFetchFrame.Progress(0L).encode()
        assertTrue(FileFetchFrame.decode(progress) is FileFetchFrame.Progress)
        val abort = FileFetchFrame.Abort(FileFetchAbortReason.NO_SPACE, null).encode()
        assertTrue(FileFetchFrame.decode(abort) is FileFetchFrame.Abort)
        val data = FileFetchFrame.Data(byteArrayOf(9)).encode()
        assertTrue(FileFetchFrame.decode(data) is FileFetchFrame.Data)
    }

    @Test
    fun fileFetch_framesCarryNoTransferId() {
        // Fetch frames are channel messages (type byte + body), not envelopes:
        // decoding them as FileHeader must fail, and vice versa.
        val fetch = FileFetchFrame.Fetch(fileId, 0L).encode()
        assertFailsWith<IllegalStateException> {
            FileHeader.decode(fetch)
        }
    }

    @Test
    fun fileFetch_fetchCarriesFileIdInClear_channelProtected() {
        // FETCH carries the fileId in clear inside the DTLS channel (design §4,
        // invariant 23): only the peer device ever sees the channel. This is
        // expected and distinct from envelope kinds, which must never carry it.
        val fetch = FileFetchFrame.Fetch(fileId = fileId, fromOffset = 0L).encode()
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

    private fun fetchOkPrefix(): ByteWriter = ByteWriter(128).apply {
        writeByte(FileFetchKind.FETCH_OK.wireValue)
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
            writeByteArray(ByteArray(32) { 3 })
            writeShort(0xfffe)
        }.toByteArray()
        assertRejectedByBound("exceeds max 255") { FileSeal.decode(wire) }
    }

    @Test
    fun fetchOk_hugeBlockCount_rejectedBeforeAllocating() {
        val wire = fetchOkPrefix().apply { writeInt(Int.MAX_VALUE) }.toByteArray()
        assertRejectedByBound("out of bounds") { FileFetchFrame.FetchOk.decode(wire) }
    }

    @Test
    fun fetchOk_shortHash_isRejected() {
        val wire = fetchOkPrefix().apply {
            writeInt(1)
            writeByteArray(ByteArray(31) { 4 })
            writeString("a")
            writeNullableString(null)
        }.toByteArray()
        assertRejectedByBound("exactly 32 bytes") { FileFetchFrame.FetchOk.decode(wire) }
    }

    @Test
    fun fetchOk_longFileName_rejectedBeforeAllocating() {
        val wire = fetchOkPrefix().apply {
            writeInt(0)
            writeShort(0xfffe)
        }.toByteArray()
        assertRejectedByBound("exceeds max 255") { FileFetchFrame.FetchOk.decode(wire) }
    }

    @Test
    fun abort_longReasonText_rejectedBeforeAllocating() {
        val wire = ByteWriter(8).apply {
            writeByte(FileFetchKind.ABORT.wireValue)
            writeByte(FileFetchAbortReason.STALLED.wireValue)
            writeShort(0xfffe)
        }.toByteArray()
        assertRejectedByBound("exceeds max") { FileFetchFrame.Abort.decode(wire) }
    }
}
