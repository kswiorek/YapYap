package org.yapyap.protocol.envelopes

import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            transferKey = ByteArray(32) { 9 },
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
            contiguousChunks = 3,
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
        val original = FilePayload.Cancel(reason = FileCancelReason.ABORT, reasonText = "user abort")
        val decoded = FilePayload.Cancel.decode(original.encode())
        assertEquals(original.reason, decoded.reason)
        assertEquals(original.reasonText, decoded.reasonText)
    }

    @Test
    fun filePayload_cancel_nullReasonText_roundTrip() {
        val original = FilePayload.Cancel(reason = FileCancelReason.DECLINED, reasonText = null)
        val decoded = FilePayload.Cancel.decode(original.encode())
        assertEquals(FileCancelReason.DECLINED, decoded.reason)
        assertNull(decoded.reasonText)
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
    fun noEnvelopeKind_carriesFileIdInClear() {
        // The fileId appears only in sealed bytes (OFFER sealed half, REQUEST sealed).
        // Outer OFFER carries only chunkSize/totalBytes/startIndex + opaque sealed bytes.
        val offer = FilePayload.Offer(
            chunkSizeBytes = 220 * 1024,
            totalBytes = 1000L,
            startIndex = 0,
            sealedOfferBytes = byteArrayOf(0x01),
        )
        val encoded = offer.encode().decodeToString()
        assertFalse(encoded.contains(fileId.value))

        val chunk = FilePayload.EncryptedChunk(0, byteArrayOf(1)).encode()
        assertFalse(chunk.decodeToString().contains(fileId.value))

        val ack = FilePayload.Ack(0, emptyList()).encode()
        assertFalse(ack.decodeToString().contains(fileId.value))

        val complete = FilePayload.Complete.encode()
        assertFalse(complete.decodeToString().contains(fileId.value))

        val cancel = FilePayload.Cancel(FileCancelReason.DECLINED, null).encode()
        assertFalse(cancel.decodeToString().contains(fileId.value))

        // Sealed plaintexts do carry it (checked separately, never on the wire in clear).
        val sealed = FilePayload.SealedOffer(
            fileId = fileId,
            blockSize = 1024 * 1024,
            blockHashes = emptyList(),
            transferKey = ByteArray(32),
            fileName = "a",
            mimeType = null,
        ).encode()
        assertTrue(sealed.decodeToString().contains(fileId.value.take(8)))
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
                assertEquals(expected.reasonText, actual.reasonText)
            }
            else -> fail("FilePayload kinds differ: ${expected::class} vs ${actual::class}")
        }
    }
}
