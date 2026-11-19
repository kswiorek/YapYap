package org.yapyap.routing.inbound.handlers

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.FileId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.*
import org.yapyap.routing.file.*
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingStatus
import org.yapyap.routing.store.FileIncomingTransfer
import org.yapyap.routing.store.FileIncomingTransferStore
import kotlin.coroutines.cancellation.CancellationException

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
    private val pusher: FilePushOps,
    private val onCoverageAck: suspend (transferId: TransferId, contiguousChunks: Int, missing: List<ChunkRange>) -> Unit,
    private val onTransferComplete: suspend (transferId: TransferId) -> Unit,
    private val onTransferCancelled: suspend (transferId: TransferId) -> Unit,
    private val onOfferReceived: suspend (fileId: FileId) -> Unit,
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
            is FilePayload.Request -> handleRequest(fileEnvelope, payload)
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
        return when (verdict) {
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
                onOfferReceived(sealed.fileId)
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
                onOfferReceived(sealed.fileId)
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

    private suspend fun handleRequest(fileEnvelope: FileEnvelope, payload: FilePayload.Request): InboundHandleResult {
        // Serve-on-REQUEST (design §9, step 16): the gate decides, then the pusher serves
        // with a fresh transfer floor-aligned at the requester's held coverage. Refusals
        // are DECLINED so the requester moves to its next candidate at once.
        val requester = try {
            ctx.devices.getAccountIdForDevice(fileEnvelope.source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_NACK_RECEIVED,
                message = "File REQUEST from unresolvable device; declining",
                fields = mapOf("sourceDeviceId" to fileEnvelope.source, "error" to e.toString()),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        } ?: return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        val allowed = try {
            ingest.canServe(payload.fileId, requester)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_NACK_RECEIVED,
                message = "File serve gate failed; deferring the REQUEST",
                fields = mapOf("fileId" to payload.fileId, "error" to e.toString()),
            )
            return InboundHandleResult.Deferred()
        }
        if (!allowed) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val served = try {
            pusher.serveFile(payload.fileId, fileEnvelope.source, payload.contiguousBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_NACK_RECEIVED,
                message = "File serve failed; deferring the REQUEST",
                fields = mapOf("fileId" to payload.fileId, "error" to e.toString()),
            )
            return InboundHandleResult.Deferred()
        }
        if (!served) {
            // Not held (or large, which is never served by transfer): the next candidate is asked.
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        return InboundHandleResult.Success(sourceAuthenticated = true)
    }
}
