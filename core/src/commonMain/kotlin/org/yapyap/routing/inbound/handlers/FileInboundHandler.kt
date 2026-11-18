package org.yapyap.routing.inbound.handlers

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.ChunkRange
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.routing.file.FileCarrier
import org.yapyap.routing.file.FileEvent
import org.yapyap.routing.file.FileFailureReason
import org.yapyap.routing.file.FileIngestPort
import org.yapyap.routing.file.FileIngestResult
import org.yapyap.routing.file.FileOfferInfo
import org.yapyap.routing.file.FileOfferVerdict
import org.yapyap.routing.file.buildFileControlEnvelope
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingTransfer
import org.yapyap.routing.store.FileIncomingTransferStore
import org.yapyap.routing.store.FileIncomingStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Real small-path inbound handler (step 11), local target only. Foreign targets are
 * rejected (relays land in Phase E); REQUESTs are declined (serve-on-REQUEST is Phase D).
 *
 * Chunk discipline (design §3.5): a CHUNK for an unknown or unprocessed `transferId` is
 * never ACKed and never stored — answered DECLINED. An unknown `transferId` is never a
 * race (chunks follow their OFFER by construction): the transfer is gone, so the sender
 * ends it quietly instead of retrying into the void.
 */
internal class FileInboundHandler(
    private val ctx: RoutingContext,
    private val incoming: FileIncomingTransferStore,
    private val ingest: FileIngestPort,
    private val carrier: FileCarrier,
    private val onCoverageAck: suspend (transferId: TransferId, contiguousChunks: Int, missing: List<ChunkRange>) -> Unit,
    private val onTransferComplete: suspend (transferId: TransferId) -> Unit,
    private val onTransferCancelled: suspend (transferId: TransferId) -> Unit,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val fileEnvelope = runCatching { FileEnvelope.decode(env.payload) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode file envelope",
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }
        if (fileEnvelope.target != ctx.localDeviceId) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val opened = try {
            ctx.envelopeProtectionService.openFile(fileEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open file envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        return when (val payload = opened.payload) {
            is FilePayload.Offer -> handleOffer(env, fileEnvelope, payload)
            is FilePayload.EncryptedChunk -> handleChunk(env, fileEnvelope, payload)
            is FilePayload.Ack -> {
                onCoverageAck(fileEnvelope.transferId, payload.contiguousChunks, payload.missingRanges)
                InboundHandleResult.Success(sourceAuthenticated = true)
            }
            is FilePayload.Complete -> {
                onTransferComplete(fileEnvelope.transferId)
                InboundHandleResult.Success(sourceAuthenticated = true)
            }
            is FilePayload.Request -> handleRequest(env, fileEnvelope, payload)
            is FilePayload.Cancel -> {
                if (incoming.find(fileEnvelope.transferId) != null) {
                    incoming.delete(fileEnvelope.transferId)
                    InboundHandleResult.Success(sourceAuthenticated = true)
                } else {
                    onTransferCancelled(fileEnvelope.transferId)
                    InboundHandleResult.Success(sourceAuthenticated = true)
                }
            }
        }
    }

    private suspend fun handleOffer(
        env: BinaryEnvelope,
        fileEnvelope: FileEnvelope,
        payload: FilePayload.Offer,
    ): InboundHandleResult {
        val sealed = try {
            ctx.envelopeProtectionService.openFileOffer(payload.sealedOfferBytes, fileEnvelope.source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open file OFFER sealed half",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        return when (
            val verdict = ingest.checkOffer(
                FileOfferInfo(
                    fileId = sealed.fileId,
                    totalBytes = payload.totalBytes,
                    blockSize = sealed.blockSize,
                    blockHashes = sealed.blockHashes,
                    fileName = sealed.fileName,
                    mimeType = sealed.mimeType,
                ),
            )
        ) {
            FileOfferVerdict.Accept -> {
                val now = ctx.clock.now()
                incoming.insert(
                    FileIncomingTransfer(
                        transferId = fileEnvelope.transferId,
                        fileId = sealed.fileId,
                        authorDevice = fileEnvelope.source,
                        key = sealed.transferKey,
                        chunkSizeBytes = payload.chunkSizeBytes,
                        totalBytes = payload.totalBytes,
                        startIndex = payload.startIndex,
                        status = FileIncomingStatus.ACTIVE,
                        createdAt = now,
                        expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
                    ),
                )
                InboundHandleResult.Success(sourceAuthenticated = true)
            }

            FileOfferVerdict.AlreadyReady -> {
                val reply = buildFileControlEnvelope(
                    protection = ctx.envelopeProtectionService,
                    transferId = fileEnvelope.transferId,
                    source = ctx.localDeviceId,
                    target = fileEnvelope.source,
                    createdAt = ctx.clock.now(),
                    expiresAt = ctx.clock.now() + ctx.routerConfig.value.fileTransferTtl,
                    payload = FilePayload.Complete,
                )
                InboundHandleResult.Success(
                    sideEffects = listOf(InboundSideEffect.EnqueueDirect(reply)),
                    sourceAuthenticated = true,
                )
            }

            is FileOfferVerdict.Refuse -> InboundHandleResult.Rejected(PacketNackReason.DECLINED)

            FileOfferVerdict.Defer -> InboundHandleResult.Deferred()
        }
    }

    private suspend fun handleChunk(
        env: BinaryEnvelope,
        fileEnvelope: FileEnvelope,
        payload: FilePayload.EncryptedChunk,
    ): InboundHandleResult {
        val row = incoming.find(fileEnvelope.transferId)
        if (row == null || row.expiresAt <= ctx.clock.now()) {
            if (row != null) incoming.delete(fileEnvelope.transferId)
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val plaintext = try {
            ctx.envelopeProtectionService.decryptFileChunk(
                transferId = fileEnvelope.transferId,
                chunkIndex = payload.chunkIndex,
                key = row.key,
                chunk = payload,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to decrypt file chunk",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        return when (
            val result = ingest.ingest(
                row.fileId,
                payload.chunkIndex.toLong() * row.chunkSizeBytes,
                plaintext,
            )
        ) {
            is FileIngestResult.Staged -> {
                carrier.emit(
                    FileEvent.IncomingProgress(
                        fileId = row.fileId,
                        contiguousBytes = result.contiguousBytes,
                        totalBytes = result.totalBytes,
                    ),
                )
                // Progress ACK back to the transfer's pusher (own chunk indices, capped).
                // Best-effort like the transport ACK: untagged, so no outbox outcome.
                val contiguous = if (result.contiguousBytes == 0L) {
                    -1
                } else {
                    ((result.contiguousBytes - 1) / row.chunkSizeBytes).toInt()
                }
                val reply = buildFileControlEnvelope(
                    protection = ctx.envelopeProtectionService,
                    transferId = fileEnvelope.transferId,
                    source = ctx.localDeviceId,
                    target = fileEnvelope.source,
                    createdAt = ctx.clock.now(),
                    expiresAt = ctx.clock.now() + ctx.routerConfig.value.fileTransferTtl,
                    payload = FilePayload.Ack(contiguous, emptyList()),
                )
                InboundHandleResult.Success(
                    sideEffects = listOf(InboundSideEffect.EnqueueDirect(reply)),
                    sourceAuthenticated = true,
                )
            }

            FileIngestResult.Duplicate ->
                InboundHandleResult.Success(sourceAuthenticated = true)

            FileIngestResult.Defer -> InboundHandleResult.Deferred()

            FileIngestResult.Refused ->
                InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
    }

    private suspend fun handleRequest(
        env: BinaryEnvelope,
        fileEnvelope: FileEnvelope,
        payload: FilePayload.Request,
    ): InboundHandleResult {
        // Step 11: no serve yet (serve-on-REQUEST is Phase D) — decline so the
        // requester moves to the next candidate. The sealed REQUEST was already
        // opened (and its author authenticated) by openFile above.
        AppLog.info(
            component = LogComponent.ROUTER,
            event = LogEvent.OUTBOX_NACK_RECEIVED,
            message = "File REQUEST declined: serve lands in Phase D",
            fields = mapOf(
                "transferId" to fileEnvelope.transferId,
                "sourceDeviceId" to fileEnvelope.source,
            ),
        )
        carrier.emit(
            FileEvent.Failed(
                fileId = payload.fileId,
                target = null,
                reason = FileFailureReason.DECLINED,
            ),
        )
        return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
    }
}
