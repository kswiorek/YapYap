package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.FILE_BLOCK_MAX_COUNT
import org.yapyap.protocol.FileFetchAbortReason
import org.yapyap.protocol.FileFetchDeclineReason
import org.yapyap.protocol.FileFetchFrame
import org.yapyap.protocol.FileFetchKind
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.TransferKey
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

class FileEnvelopeCodecTest {

    private val source = PeerId("file-src")
    private val target = PeerId("file-dst")
    private val nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 7 }
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    @Test
    fun fileEnvelopeKind_wireValuesDistinct() {
        val wires = FileEnvelopeKind.entries.map { it.wireValue }.toSet()
        assertEquals(FileEnvelopeKind.entries.size, wires.size)
    }

    @Test
    fun filePayload_offer_encodeDecode_roundTrip() {
        val original = FilePayload.Offer(
            chunkSizeBytes = 100,
            totalBytes = 999L,
            startIndex = 0,
            sealedOfferBytes = byteArrayOf(1, 2, 3),
        )
        val decoded = FilePayload.Offer.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun filePayload_sealedOffer_encodeDecode_roundTrip() {
        val original = FilePayload.SealedOffer(
            fileId = fileId,
            blockSize = 1024 * 1024,
            blockHashes = listOf(ByteArray(32) { it.toByte() }),
            transferKey = TransferKey(ByteArray(32) { 9 }),
            fileName = "doc.bin",
            mimeType = "application/octet-stream",
        )
        val decoded = FilePayload.SealedOffer.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun filePayload_encryptedChunk_encodeDecode_roundTrip() {
        val original = FilePayload.EncryptedChunk(
            chunkIndex = 0,
            chunkCiphertext = byteArrayOf(1, 2, 3),
        )
        val decoded = FilePayload.EncryptedChunk.decode(original.encode())
        assertEquals(original.chunkIndex, decoded.chunkIndex)
        assertContentEquals(original.chunkCiphertext, decoded.chunkCiphertext)
    }

    @Test
    fun filePayload_ack_encodeDecode_roundTrip() {
        val original = FilePayload.Ack(
            highestContiguousIndex = 3,
            missingRanges = listOf(ChunkRange(5, 2), ChunkRange(9, 1)),
        )
        val decoded = FilePayload.Ack.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun filePayload_complete_encodeDecode_roundTrip() {
        val original = FilePayload.Complete
        val decoded = FilePayload.Complete.decode(original.encode())
        assertSame(original, decoded)
    }

    @Test
    fun filePayload_request_encodeDecode_roundTrip() {
        val original = FilePayload.Request(
            fileId = fileId,
            contiguousBytes = 1234L,
            missingRanges = listOf(ByteRange(1234L, 100L)),
        )
        val decoded = FilePayload.Request.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun filePayload_cancel_encodeDecode_roundTrip() {
        val original = FilePayload.Cancel(reason = FileCancelReason.ABORT)
        val decoded = FilePayload.Cancel.decode(original.encode())
        assertEquals(original.reason, decoded.reason)
    }

    @Test
    fun filePayload_cancel_allReasons_roundTrip() {
        for (reason in FileCancelReason.entries) {
            val decoded = FilePayload.Cancel.decode(FilePayload.Cancel(reason).encode())
            assertEquals(reason, decoded.reason)
        }
    }

    @Test
    fun fileEnvelope_full_encodeDecode_offer_roundTrip() {
        val payload = FilePayload.Offer(
            chunkSizeBytes = 512,
            totalBytes = 0L,
            startIndex = 0,
            sealedOfferBytes = byteArrayOf(1),
        )
        val env = FileEnvelope(
            transferId = TransferId(Uuid.random()),
            source = source,
            target = target,
            createdAt = epochSeconds(123L),
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = payload.encode(),
        )
        val round = FileEnvelope.decode(env.encode())
        assertFileEnvelopeEquals(env, round)
    }

    @Test
    fun fileEnvelope_full_encodeDecode_chunk_roundTrip() {
        val payload = FilePayload.EncryptedChunk(
            chunkIndex = 2,
            chunkCiphertext = ByteArray(16) { 1 },
        )
        val env = FileEnvelope(
            transferId = TransferId(Uuid.random()),
            source = source,
            target = target,
            createdAt = epochSeconds(0L),
            nonce = nonce,
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(32) { 2 },
            payload = payload.encode(),
        )
        val round = FileEnvelope.decode(env.encode())
        assertFileEnvelopeEquals(env, round)
    }

    @Test
    fun fileEnvelope_decode_rejectsWrongMagic() {
        val good = FileEnvelope(
            transferId = TransferId(Uuid.random()),
            source = source,
            target = target,
            createdAt = epochSeconds(0L),
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = FilePayload.Complete.encode(),
        ).encode()
        val bad = good.copyOf()
        bad[0] = 0x00
        assertFailsWith<IllegalArgumentException> {
            FileEnvelope.decode(bad)
        }
    }

    @Test
    fun fileId_isEncodedOnlyByThePayloadsProtectionSeals() {
        // The encoded REQUEST and sealed OFFER half carry the fileId in clear, which is why
        // protection ratchet-seals exactly those two; FileProtectionTest checks the protected wire
        // bytes of every kind. No public payload type has a fileId field at all.
        val request = FilePayload.Request(fileId, 0L, emptyList()).encode()
        assertTrue(request.decodeToString().contains(fileId.value))
        val sealed = FilePayload.SealedOffer(
            fileId = fileId,
            blockSize = 1024 * 1024,
            blockHashes = emptyList(),
            transferKey = TransferKey(ByteArray(32)),
            fileName = "a",
            mimeType = null,
        ).encode()
        assertTrue(sealed.decodeToString().contains(fileId.value))
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
        // decoding them as FilePayload must fail, and vice versa.
        val fetch = FileFetchFrame.Fetch(fileId, 0L).encode()
        assertFailsWith<IllegalStateException> {
            FilePayload.decode(fetch)
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

    private fun assertFileEnvelopeEquals(expected: FileEnvelope, actual: FileEnvelope) {
        assertEquals(expected.transferId, actual.transferId)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.target, actual.target)
        assertEquals(expected.createdAt, actual.createdAt)
        assertContentEquals(expected.nonce, actual.nonce)
        assertEquals(expected.securityScheme, actual.securityScheme)
        when {
            expected.signature == null -> assertNull(actual.signature)
            else -> assertContentEquals(expected.signature, actual.signature!!)
        }
        assertFilePayloadEquals(expected.decodePayload(), actual.decodePayload())
    }

    private fun assertFilePayloadEquals(expected: FilePayload, actual: FilePayload) {
        when {
            expected is FilePayload.Offer && actual is FilePayload.Offer ->
                assertEquals(expected, actual)
            expected is FilePayload.EncryptedChunk && actual is FilePayload.EncryptedChunk -> {
                assertEquals(expected.chunkIndex, actual.chunkIndex)
                assertContentEquals(expected.chunkCiphertext, actual.chunkCiphertext)
            }
            expected is FilePayload.Ack && actual is FilePayload.Ack ->
                assertEquals(expected, actual)

            expected is FilePayload.Complete && actual is FilePayload.Complete -> {}
            expected is FilePayload.Request && actual is FilePayload.Request ->
                assertEquals(expected, actual)
            expected is FilePayload.Cancel && actual is FilePayload.Cancel -> {
                assertEquals(expected.reason, actual.reason)
            }
            else -> fail("FilePayload kinds differ: ${expected::class} vs ${actual::class}")
        }
    }

    // Each hostile input below carries only the oversized prefix, nothing after it. A decoder
    // that trusted the prefix would either try to allocate it (an OutOfMemoryError for a count
    // of Int.MAX_VALUE) or run off the end ("Unexpected end of envelope"); the bounded decoder
    // rejects the prefix itself, which is what the message checks prove.

    private fun assertRejectedByBound(expected: String, decode: () -> Unit) {
        val e = assertFailsWith<IllegalArgumentException> { decode() }
        assertTrue(e.message.orEmpty().contains(expected), "rejected by its bound, not later: ${e.message}")
    }

    private fun sealedOfferPrefix(): ByteWriter = ByteWriter(128).apply {
        writeString(fileId.value)
        writeInt(1024 * 1024)
    }

    private fun fetchOkPrefix(): ByteWriter = ByteWriter(128).apply {
        writeByte(FileFetchKind.FETCH_OK.wireValue)
        writeLong(100L)
        writeInt(1024 * 1024)
    }

    @Test
    fun request_hugeRangeCount_rejectedBeforeAllocating() {
        val wire = ByteWriter(80).apply {
            writeByte(FileEnvelopeKind.REQUEST.wireValue)
            writeString(fileId.value)
            writeLong(0L)
            writeInt(Int.MAX_VALUE)
        }.toByteArray()
        assertRejectedByBound("out of bounds") { FilePayload.Request.decode(wire) }
    }

    @Test
    fun ack_hugeRangeCount_rejectedBeforeAllocating() {
        val wire = ByteWriter(16).apply {
            writeByte(FileEnvelopeKind.ACK.wireValue)
            writeInt(0)
            writeInt(Int.MAX_VALUE)
        }.toByteArray()
        assertRejectedByBound("out of bounds") { FilePayload.Ack.decode(wire) }
    }

    @Test
    fun sealedOffer_hugeBlockCount_rejectedBeforeAllocating() {
        val wire = sealedOfferPrefix().apply { writeInt(Int.MAX_VALUE) }.toByteArray()
        assertRejectedByBound("out of bounds") { FilePayload.SealedOffer.decode(wire) }
    }

    @Test
    fun sealedOffer_oneOverMaxBlockCount_isRejected() {
        val wire = sealedOfferPrefix().apply { writeInt(FILE_BLOCK_MAX_COUNT + 1) }.toByteArray()
        assertRejectedByBound("out of bounds") { FilePayload.SealedOffer.decode(wire) }
    }

    @Test
    fun sealedOffer_hugeHashLength_rejectedBeforeAllocating() {
        val wire = sealedOfferPrefix().apply {
            writeInt(1)
            writeInt(Int.MAX_VALUE)
        }.toByteArray()
        assertRejectedByBound("exceeds max 32") { FilePayload.SealedOffer.decode(wire) }
    }

    @Test
    fun sealedOffer_shortHash_isRejected() {
        val wire = sealedOfferPrefix().apply {
            writeInt(1)
            writeByteArray(ByteArray(31) { 1 })
            writeByteArray(ByteArray(32) { 2 })
            writeString("a")
            writeNullableString(null)
        }.toByteArray()
        assertRejectedByBound("exactly 32 bytes") { FilePayload.SealedOffer.decode(wire) }
    }

    @Test
    fun sealedOffer_longFileName_rejectedBeforeAllocating() {
        val wire = sealedOfferPrefix().apply {
            writeInt(0)
            writeByteArray(ByteArray(32) { 3 })
            writeShort(0xfffe)
        }.toByteArray()
        assertRejectedByBound("exceeds max 255") { FilePayload.SealedOffer.decode(wire) }
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
