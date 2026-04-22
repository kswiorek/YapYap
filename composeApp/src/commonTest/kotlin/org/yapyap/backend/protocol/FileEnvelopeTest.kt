package org.yapyap.backend.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileEnvelopeTest {

    @Test
    fun roundTripsEncodedEnvelope() {
        val envelope = FileEnvelope(
            transferId = "file-transfer-01",
            kind = FileEnvelopeKind.CHUNK,
            source = PeerId(accountName = "alice", deviceId = "alice-phone"),
            target = PeerId(accountName = "bob", deviceId = "bob-pi"),
            createdAtEpochSeconds = 1_700_000_100L,
            nonce = byteArrayOf(1, 2, 3, 4),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = byteArrayOf(9, 8, 7),
            protectedPayload = "chunk-ciphertext".encodeToByteArray(),
        )

        val decoded = FileEnvelope.decode(envelope.encode())

        assertEquals(envelope.transferId, decoded.transferId)
        assertEquals(envelope.kind, decoded.kind)
        assertEquals(envelope.source, decoded.source)
        assertEquals(envelope.target, decoded.target)
        assertEquals(envelope.createdAtEpochSeconds, decoded.createdAtEpochSeconds)
        assertContentEquals(envelope.nonce, decoded.nonce)
        assertEquals(envelope.securityScheme, decoded.securityScheme)
        assertContentEquals(envelope.signature, decoded.signature)
        assertContentEquals(envelope.protectedPayload, decoded.protectedPayload)
    }

    @Test
    fun canBeCarriedInsideBinaryEnvelopePayload() {
        val offerPayload = FileOfferPayload(
            fileNameHint = "photo.jpg",
            mimeType = "image/jpeg",
            totalBytes = 1024,
            chunkSizeBytes = 256,
            chunkCount = 4,
            objectHash = byteArrayOf(1, 2, 3, 4),
            control = FileControlPayload(
                transferClass = FileTransferClass.SMALL_STORE_FORWARD,
                preferredTransport = FileTransportPreference.TOR,
                supportsResume = true,
                maxInFlightChunks = 8,
            ),
        )
        val payload = FileEnvelope(
            transferId = "file-transfer-02",
            kind = FileEnvelopeKind.OFFER,
            source = PeerId(accountName = "alice", deviceId = "alice-phone"),
            target = PeerId(accountName = "bob", deviceId = "bob-pi"),
            createdAtEpochSeconds = 1_700_000_200L,
            nonce = byteArrayOf(5, 6, 7, 8),
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            protectedPayload = offerPayload.encode(),
        ).encode()

        val binaryEnvelope = BinaryEnvelope(
            packetId = PacketId.fromHex("102030405060708090a0b0c0d0e0f000"),
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = 1_700_000_000L,
            expiresAtEpochSeconds = 1_700_000_300L,
            hopCount = 0,
            route = EnvelopeRoute(
                destinationAccount = "bob",
                destinationDevice = "bob-pi",
                nextHopDevice = null,
            ),
            payload = payload,
        )

        val decodedOuter = BinaryEnvelope.decode(binaryEnvelope.encode())
        val decodedInner = FileEnvelope.decode(decodedOuter.payload)
        val decodedOffer = decodedInner.decodeOfferPayload()

        assertEquals("file-transfer-02", decodedInner.transferId)
        assertEquals(FileEnvelopeKind.OFFER, decodedInner.kind)
        assertEquals(offerPayload.fileNameHint, decodedOffer.fileNameHint)
        assertEquals(offerPayload.mimeType, decodedOffer.mimeType)
        assertEquals(offerPayload.totalBytes, decodedOffer.totalBytes)
        assertEquals(offerPayload.chunkSizeBytes, decodedOffer.chunkSizeBytes)
        assertEquals(offerPayload.chunkCount, decodedOffer.chunkCount)
        assertContentEquals(offerPayload.objectHash, decodedOffer.objectHash)
    }

    @Test
    fun roundTripsKindSpecificPayloadCodecs() {
        val offer = FileOfferPayload(
            fileNameHint = "x.png",
            mimeType = "image/png",
            totalBytes = 2048,
            chunkSizeBytes = 512,
            chunkCount = 4,
            objectHash = byteArrayOf(7, 7, 7),
            control = FileControlPayload(
                transferClass = FileTransferClass.LARGE_P2P,
                preferredTransport = FileTransportPreference.WEBRTC_DATA,
                supportsResume = true,
                maxInFlightChunks = 32,
            ),
        )
        val decodedOffer = FileOfferPayload.decode(offer.encode())
        assertEquals(offer.fileNameHint, decodedOffer.fileNameHint)
        assertEquals(offer.mimeType, decodedOffer.mimeType)
        assertEquals(offer.totalBytes, decodedOffer.totalBytes)
        assertEquals(offer.chunkSizeBytes, decodedOffer.chunkSizeBytes)
        assertEquals(offer.chunkCount, decodedOffer.chunkCount)
        assertContentEquals(offer.objectHash, decodedOffer.objectHash)
        assertEquals(offer.control, decodedOffer.control)

        val chunk = FileChunkPayload(
            chunkIndex = 2,
            chunkCount = 4,
            chunkCiphertext = byteArrayOf(8, 8, 8, 8),
        )
        val decodedChunk = FileChunkPayload.decode(chunk.encode())
        assertEquals(chunk.chunkIndex, decodedChunk.chunkIndex)
        assertEquals(chunk.chunkCount, decodedChunk.chunkCount)
        assertContentEquals(chunk.chunkCiphertext, decodedChunk.chunkCiphertext)

        val ack = FileAckPayload(
            highestContiguousChunk = 5,
            missingChunkIndices = intArrayOf(6, 9, 11),
        )
        val decodedAck = FileAckPayload.decode(ack.encode())
        assertEquals(ack.highestContiguousChunk, decodedAck.highestContiguousChunk)
        assertContentEquals(ack.missingChunkIndices, decodedAck.missingChunkIndices)

        val complete = FileCompletePayload(
            objectHash = byteArrayOf(3, 2, 1),
        )
        val decodedComplete = FileCompletePayload.decode(complete.encode())
        assertContentEquals(complete.objectHash, decodedComplete.objectHash)

        val cancel = FileCancelPayload(
            reasonCode = 2,
            reasonText = "receiver offline",
        )
        assertEquals(cancel, FileCancelPayload.decode(cancel.encode()))
    }

    @Test
    fun rejectsKindSpecificDecodeForMismatchedKind() {
        val envelope = FileEnvelope(
            transferId = "file-transfer-03",
            kind = FileEnvelopeKind.ACK,
            source = PeerId(accountName = "alice", deviceId = "alice-phone"),
            target = PeerId(accountName = "bob", deviceId = "bob-pi"),
            createdAtEpochSeconds = 1_700_000_300L,
            nonce = byteArrayOf(9, 9, 9, 9),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = byteArrayOf(1),
            protectedPayload = FileAckPayload(
                highestContiguousChunk = 1,
                missingChunkIndices = intArrayOf(2, 3),
            ).encode(),
        )

        assertFailsWith<IllegalArgumentException> {
            envelope.decodeChunkPayload()
        }
    }

    @Test
    fun rejectsInvalidMagic() {
        val bad = byteArrayOf(0, 1, 2, 3, 4)
        assertFailsWith<IllegalArgumentException> {
            FileEnvelope.decode(bad)
        }
    }
}
