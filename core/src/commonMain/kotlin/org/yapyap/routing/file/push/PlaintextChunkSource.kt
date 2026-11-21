package org.yapyap.routing.file.push

import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protection.envelope.FileOfferPublicHalf
import org.yapyap.protection.envelope.FileOfferSealedManifest
import org.yapyap.protection.envelope.FileProtectContext
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePush
import kotlin.uuid.Uuid

/**
 * Builds finished CHUNK and OFFER envelopes from the orchestrator's plaintext source.
 * Reads through [FilePlaintextSource] (block-checked at the source), encrypts with the row's
 * key and signs through protection. The orchestrator never sees a key.
 *
 * One stream per transfer, opened at the first chunk and reused while chunks are asked in
 * order, so each block is verified once per serve instead of once per chunk. A seek
 * (restart, re-queue) re-opens at that offset.
 */
internal class PlaintextChunkSource(
    private val ctx: RoutingContext,
    private val protection: FileEnvelopeProtection,
    private val plaintext: FilePlaintextSource,
    private val row: suspend () -> FilePush,
    private val manifest: suspend () -> FileManifest?,
) : ChunkSource {
    private var stream: PlaintextStream? = null
    private var streamPos: Long? = null

    override suspend fun offer(): BinaryEnvelope {
        val current = row()
        val info = manifest() ?: throw IllegalStateException("File not held for ${current.fileId}")
        val envelope = protection.protectOffer(
            publicHalf = FileOfferPublicHalf(
                chunkSizeBytes = current.chunkSizeBytes,
                totalBytes = current.totalBytes,
                startIndex = current.startIndex,
            ),
            sealed = FileOfferSealedManifest(
                fileId = info.fileId,
                blockSize = info.blockSize,
                blockHashes = info.blockHashes,
                fileName = info.fileName,
                mimeType = info.mimeType,
            ),
            key = current.key,
            ctx = FileProtectContext(
                sourceDeviceId = ctx.localDeviceId,
                targetDeviceId = current.targetDevice,
                createdAt = ctx.clock.now(),
                transferId = current.transferId,
            ),
        )
        return BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = ctx.clock.now(),
            expiresAt = current.expiresAt,
            source = ctx.localDeviceId,
            target = current.viaRelay ?: current.targetDevice,
            payload = envelope.encode(),
        )
    }

    override suspend fun chunk(index: Int): ChunkResult {
        val current = row()
        val offset = index.toLong() * current.chunkSizeBytes
        val length = minOf(current.chunkSizeBytes.toLong(), current.totalBytes - offset).toInt()
        if (length <= 0) return ChunkResult.SourceLost
        val bytes = readVerified(current.fileId, offset, length) ?: return ChunkResult.SourceLost
        if (offset + bytes.size >= current.totalBytes) closeStream()
        val ciphertext = protection.encryptChunk(
            current.transferId,
            index,
            current.key,
            bytes,
        )
        val envelope = protection.protect(
            FilePayload.EncryptedChunk(index, ciphertext),
            FileProtectContext(
                sourceDeviceId = ctx.localDeviceId,
                targetDeviceId = current.targetDevice,
                createdAt = ctx.clock.now(),
                transferId = current.transferId,
            ),
        )
        return ChunkResult.Envelope(
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.FILE,
                dispositionRequested = true,
                createdAt = ctx.clock.now(),
                expiresAt = current.expiresAt,
                source = ctx.localDeviceId,
                target = current.viaRelay ?: current.targetDevice,
                payload = envelope.encode(),
            ),
        )
    }

    private suspend fun readVerified(fileId: FileId, offset: Long, length: Int): ByteArray? {
        var s = stream
        if (s == null || streamPos != offset) {
            s?.close()
            s = plaintext.open(fileId, offset) ?: run {
                stream = null
                streamPos = null
                return null
            }
            stream = s
            streamPos = offset
        }
        val out = ByteArray(length)
        var got = 0
        while (got < length) {
            when (val r = s.read(length - got)) {
                is PlaintextRead.Bytes -> {
                    r.bytes.copyInto(out, got)
                    got += r.bytes.size
                    streamPos = (streamPos ?: offset) + r.bytes.size
                }

                is PlaintextRead.Eof -> {
                    // The source shrank mid-serve: end it here instead of sending a short chunk.
                    closeStream()
                    return null
                }

                is PlaintextRead.SourceLost -> {
                    closeStream()
                    return null
                }
            }
        }
        return out
    }

    override fun close() {
        closeStream()
    }

    private fun closeStream() {
        val s = stream
        stream = null
        streamPos = null
        s?.close()
    }
}
