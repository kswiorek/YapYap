package org.yapyap.routing.file

import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protection.envelope.FileProtectContext
import org.yapyap.protocol.MIN_FILE_CHUNK_SIZE_BYTES
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Builds a signed FILE control envelope (COMPLETE/CANCEL) and wraps it for the outbox. */
internal suspend fun buildFileControlEnvelope(
    protection: FileEnvelopeProtection,
    transferId: TransferId,
    source: PeerId,
    target: PeerId,
    createdAt: Instant,
    expiresAt: Instant,
    payload: FilePayload,
): BinaryEnvelope {
    val envelope = protection.protect(
        payload,
        FileProtectContext(
            sourceDeviceId = source,
            targetDeviceId = target,
            createdAt = createdAt,
            transferId = transferId,
        ),
    )
    return BinaryEnvelope(
        packetId = Uuid.random(),
        packetType = PacketType.FILE,
        dispositionRequested = true,
        createdAt = createdAt,
        expiresAt = expiresAt,
        source = source,
        target = target,
        payload = envelope.encode(),
    )
}

/**
 * Exact ciphertext length of chunk [index] of a transfer: its plaintext slice plus the AEAD
 * overhead. A CHUNK of any other length is not one the author sealed for that index.
 */
internal fun expectedChunkCiphertextBytes(totalBytes: Long, chunkSizeBytes: Int, index: Int): Long =
    minOf(chunkSizeBytes.toLong(), totalBytes - index.toLong() * chunkSizeBytes) +
            FileEnvelopeProtection.CHUNK_OVERHEAD_BYTES

/** Chunks of a transfer of [totalBytes] split at [chunkSizeBytes]; 0 for an empty file. */
internal fun fileChunkCount(totalBytes: Long, chunkSizeBytes: Int): Int =
    if (totalBytes == 0L) 0 else ((totalBytes - 1) / chunkSizeBytes + 1).toInt()

/**
 * The OFFER's public half, checked identically by the target and the relay door (design §3.5,
 * §7.1): a chunk size of at least [MIN_FILE_CHUNK_SIZE_BYTES], a small-path size (an empty file is
 * valid: it has no chunks), and a start index within the chunk range.
 */
internal fun isValidOfferPublicHalf(payload: FilePayload.Offer): Boolean {
    if (payload.chunkSizeBytes < MIN_FILE_CHUNK_SIZE_BYTES) return false
    if (payload.totalBytes < 0 || payload.totalBytes > SMALL_FILE_MAX_BYTES) return false
    return payload.startIndex in 0..fileChunkCount(payload.totalBytes, payload.chunkSizeBytes)
}
