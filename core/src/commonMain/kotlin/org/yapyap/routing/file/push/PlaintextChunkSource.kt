package org.yapyap.routing.file.push

import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReadResult
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePush
import kotlin.uuid.Uuid

/**
 * Builds finished CHUNK and OFFER envelopes from the orchestrator's plaintext source.
 * Reads through [FilePlaintextSource] (block-checked at the source), encrypts with the row's
 * key and signs through protection. The orchestrator never sees a key.
 */
internal class PlaintextChunkSource(
    private val ctx: RoutingContext,
    private val plaintext: FilePlaintextSource,
    private val row: suspend () -> FilePush,
    private val manifest: suspend () -> FileManifest?,
) : ChunkSource {
    override suspend fun offer(): BinaryEnvelope {
        val current = row()
        val info = manifest() ?: throw IllegalStateException("File not held for ${current.fileId}")
        val sealed = ctx.envelopeProtectionService.sealFileOffer(
            FilePayload.SealedOffer(
                fileId = info.fileId,
                blockSize = info.blockSize,
                blockHashes = info.blockHashes,
                transferKey = current.key,
                fileName = info.fileName,
                mimeType = info.mimeType,
            ),
            current.targetDevice,
        )
        val envelope = ctx.envelopeProtectionService.protectFile(
            FilePayload.Offer(
                chunkSizeBytes = current.chunkSizeBytes,
                totalBytes = current.totalBytes,
                startIndex = current.startIndex,
                sealedOfferBytes = sealed,
            ),
            EnvelopeProtectContext(
                createdAt = ctx.clock.now(),
                sourceDeviceId = ctx.localDeviceId,
                targetDeviceId = current.targetDevice,
                securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
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
        val bytes = when (
            val read = plaintext.read(current.fileId, offset, length)
        ) {
            is FileReadResult.Ok -> read.bytes
            is FileReadResult.NotHeld -> return ChunkResult.SourceLost
            is FileReadResult.SourceLost -> return ChunkResult.SourceLost
        }
        val ciphertext = ctx.envelopeProtectionService.encryptFileChunk(
            current.transferId,
            index,
            current.key,
            bytes,
        )
        val envelope = ctx.envelopeProtectionService.protectFile(
            FilePayload.EncryptedChunk(index, ciphertext),
            EnvelopeProtectContext(
                createdAt = ctx.clock.now(),
                sourceDeviceId = ctx.localDeviceId,
                targetDeviceId = current.targetDevice,
                securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
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
}
