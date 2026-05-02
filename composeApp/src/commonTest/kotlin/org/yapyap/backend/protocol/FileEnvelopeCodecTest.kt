package org.yapyap.backend.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

class FileEnvelopeCodecTest {

    private val source = PeerId("file-src")
    private val target = PeerId("file-dst")
    private val nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 7 }

    private val control = FileControlPayload(
        transferClass = FileTransferClass.LARGE_P2P,
        preferredTransport = FileTransportPreference.WEBRTC_DATA,
        supportsResume = true,
        maxInFlightChunks = 8,
    )

    @Test
    fun fileEnvelopeKind_wireValuesDistinct() {
        val wires = FileEnvelopeKind.entries.map { it.wireValue }.toSet()
        assertEquals(FileEnvelopeKind.entries.size, wires.size)
    }

    @Test
    fun filePayload_offer_encodeDecode_roundTrip() {
        val original = FilePayload.Offer(
            fileNameHint = "doc.bin",
            mimeType = "application/octet-stream",
            totalBytes = 999L,
            chunkSizeBytes = 100,
            chunkCount = 10,
            objectHash = ByteArray(32) { it.toByte() },
            control = control,
        )
        val decoded = FilePayload.Offer.decode(original.encode())
        assertOfferEquals(original, decoded)
    }

    @Test
    fun filePayload_encryptedChunk_encodeDecode_roundTrip() {
        val original = FilePayload.EncryptedChunk(
            chunkIndex = 0,
            chunkCount = 5,
            chunkCiphertext = byteArrayOf(1, 2, 3),
        )
        val decoded = FilePayload.EncryptedChunk.decode(original.encode())
        assertEquals(original.chunkIndex, decoded.chunkIndex)
        assertEquals(original.chunkCount, decoded.chunkCount)
        assertContentEquals(original.chunkCiphertext, decoded.chunkCiphertext)
    }

    @Test
    fun filePayload_ack_encodeDecode_roundTrip() {
        val original = FilePayload.Ack(
            highestContiguousChunk = 3,
            missingChunkIndices = intArrayOf(1, 4),
        )
        val decoded = FilePayload.Ack.decode(original.encode())
        assertEquals(original.highestContiguousChunk, decoded.highestContiguousChunk)
        assertContentEquals(original.missingChunkIndices, decoded.missingChunkIndices)
    }

    @Test
    fun filePayload_complete_encodeDecode_roundTrip() {
        val original = FilePayload.Complete(objectHash = byteArrayOf(9, 9))
        val decoded = FilePayload.Complete.decode(original.encode())
        assertContentEquals(original.objectHash, decoded.objectHash)
    }

    @Test
    fun filePayload_cancel_encodeDecode_roundTrip() {
        val original = FilePayload.Cancel(reasonCode = 7, reasonText = "user abort")
        val decoded = FilePayload.Cancel.decode(original.encode())
        assertEquals(original.reasonCode, decoded.reasonCode)
        assertEquals(original.reasonText, decoded.reasonText)
    }

    @Test
    fun filePayload_cancel_nullReasonText_roundTrip() {
        val original = FilePayload.Cancel(reasonCode = 0, reasonText = null)
        val decoded = FilePayload.Cancel.decode(original.encode())
        assertEquals(0, decoded.reasonCode)
        assertNull(decoded.reasonText)
    }

    @Test
    fun fileEnvelope_full_encodeDecode_offer_roundTrip() {
        val payload = FilePayload.Offer(
            fileNameHint = null,
            mimeType = null,
            totalBytes = 0L,
            chunkSizeBytes = 512,
            chunkCount = 1,
            objectHash = byteArrayOf(1),
            control = FileControlPayload(
                transferClass = FileTransferClass.SMALL_STORE_FORWARD,
                preferredTransport = FileTransportPreference.TOR,
                supportsResume = false,
                maxInFlightChunks = 1,
            ),
        )
        val env = FileEnvelope(
            transferId = "tid-1",
            source = source,
            target = target,
            createdAtEpochSeconds = 123L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = payload,
        )
        val round = FileEnvelope.decode(env.encode())
        assertFileEnvelopeEquals(env, round)
    }

    @Test
    fun fileEnvelope_full_encodeDecode_chunk_roundTrip() {
        val payload = FilePayload.EncryptedChunk(
            chunkIndex = 2,
            chunkCount = 10,
            chunkCiphertext = ByteArray(16) { 1 },
        )
        val env = FileEnvelope(
            transferId = "tid-chunk",
            source = source,
            target = target,
            createdAtEpochSeconds = 0L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(32) { 2 },
            payload = payload,
        )
        val round = FileEnvelope.decode(env.encode())
        assertFileEnvelopeEquals(env, round)
    }

    @Test
    fun fileEnvelope_decode_rejectsWrongMagic() {
        val good = FileEnvelope(
            transferId = "t",
            source = source,
            target = target,
            createdAtEpochSeconds = 0L,
            nonce = nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = FilePayload.Complete(objectHash = byteArrayOf(3)),
        ).encode()
        val bad = good.copyOf()
        bad[0] = 0x00
        assertFailsWith<IllegalArgumentException> {
            FileEnvelope.decode(bad)
        }
    }

    @Test
    fun fileEnvelope_init_rejectsBlankTransferId() {
        assertFailsWith<IllegalArgumentException> {
            FileEnvelope(
                transferId = " ",
                source = source,
                target = target,
                createdAtEpochSeconds = 0L,
                nonce = nonce,
                securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                signature = null,
                payload = FilePayload.Complete(objectHash = byteArrayOf(1)),
            )
        }
    }

    private fun assertFileEnvelopeEquals(expected: FileEnvelope, actual: FileEnvelope) {
        assertEquals(expected.transferId, actual.transferId)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.target, actual.target)
        assertEquals(expected.createdAtEpochSeconds, actual.createdAtEpochSeconds)
        assertContentEquals(expected.nonce, actual.nonce)
        assertEquals(expected.securityScheme, actual.securityScheme)
        when {
            expected.signature == null -> assertNull(actual.signature)
            else -> assertContentEquals(expected.signature, actual.signature!!)
        }
        assertFilePayloadEquals(expected.payload, actual.payload)
    }

    private fun assertFilePayloadEquals(expected: FilePayload, actual: FilePayload) {
        when {
            expected is FilePayload.Offer && actual is FilePayload.Offer -> assertOfferEquals(expected, actual)
            expected is FilePayload.EncryptedChunk && actual is FilePayload.EncryptedChunk -> {
                assertEquals(expected.chunkIndex, actual.chunkIndex)
                assertEquals(expected.chunkCount, actual.chunkCount)
                assertContentEquals(expected.chunkCiphertext, actual.chunkCiphertext)
            }
            expected is FilePayload.Ack && actual is FilePayload.Ack -> {
                assertEquals(expected.highestContiguousChunk, actual.highestContiguousChunk)
                assertContentEquals(expected.missingChunkIndices, actual.missingChunkIndices)
            }
            expected is FilePayload.Complete && actual is FilePayload.Complete ->
                assertContentEquals(expected.objectHash, actual.objectHash)
            expected is FilePayload.Cancel && actual is FilePayload.Cancel -> {
                assertEquals(expected.reasonCode, actual.reasonCode)
                assertEquals(expected.reasonText, actual.reasonText)
            }
            else -> fail("FilePayload kinds differ: ${expected::class} vs ${actual::class}")
        }
    }

    private fun assertOfferEquals(expected: FilePayload.Offer, actual: FilePayload.Offer) {
        assertEquals(expected.fileNameHint, actual.fileNameHint)
        assertEquals(expected.mimeType, actual.mimeType)
        assertEquals(expected.totalBytes, actual.totalBytes)
        assertEquals(expected.chunkSizeBytes, actual.chunkSizeBytes)
        assertEquals(expected.chunkCount, actual.chunkCount)
        assertContentEquals(expected.objectHash, actual.objectHash)
        assertEquals(expected.control.transferClass, actual.control.transferClass)
        assertEquals(expected.control.preferredTransport, actual.control.preferredTransport)
        assertEquals(expected.control.supportsResume, actual.control.supportsResume)
        assertEquals(expected.control.maxInFlightChunks, actual.control.maxInFlightChunks)
    }
}
