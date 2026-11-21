package org.yapyap.routing.inbound.handlers

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FileIncomingTransfers
import org.yapyap.routing.file.FileTransferSignals
import org.yapyap.routing.file.buildFileControlEnvelope
import org.yapyap.routing.file.fileChunkCount
import org.yapyap.routing.file.isValidOfferPublicHalf
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingStatus
import org.yapyap.routing.store.FileIncomingTransfer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Target-side small-path handler (design §3.5–§3.6, §8): OFFER / CHUNK / ACK / COMPLETE / CANCEL
 * for envelopes whose inner target is this device. Signals the push engine through
 * [FileTransferSignals]; never touches the relay store.
 */
internal class FileTargetHandler(
    private val ctx: RoutingContext,
    private val incoming: FileIncomingTransfers,
    private val ingest: FileIngestPort,
    private val signals: FileTransferSignals,
    private val onOfferReceived: suspend (FileId) -> Unit,
) {
    suspend fun handleOffer(
        env: BinaryEnvelope,
        fileEnvelope: FileEnvelope,
        payload: FilePayload.Offer,
    ): InboundHandleResult {
        // Public-half validation, exactly as the relay door does: a malformed OFFER is never
        // stored and never sealed-opened. The door answers DECLINED; the target does the same.
        if (!isValidOfferPublicHalf(payload)) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        // Idempotent re-OFFER: the row already exists (the sender retries until our ACK
        // landed). An expired row is dropped and the OFFER processed fresh instead.
        incoming.find(fileEnvelope.transferId)?.let { existing ->
            if (ctx.clock.now() >= existing.expiresAt) {
                incoming.end(fileEnvelope.transferId)
            } else {
                return InboundHandleResult.Success(sourceAuthenticated = true)
            }
        }
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
            FileManifest(
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
                incoming.accept(
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
                AppLog.info(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_OFFER_ACCEPTED,
                    message = "File OFFER accepted",
                    fields = mapOf("transferId" to fileEnvelope.transferId, "fileId" to sealed.fileId),
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

    suspend fun handleChunk(
        env: BinaryEnvelope,
        fileEnvelope: FileEnvelope,
        payload: FilePayload.EncryptedChunk,
    ): InboundHandleResult {
        val row = incoming.find(fileEnvelope.transferId)
        if (row == null || row.expiresAt <= ctx.clock.now()) {
            if (row != null) incoming.end(fileEnvelope.transferId)
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        // Only the transfer's author may feed it chunks, and only inside its covered suffix.
        // Anything else is answered DECLINED before a single decrypt is burned.
        if (fileEnvelope.source != row.authorDevice) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val chunkCount = fileChunkCount(row.totalBytes, row.chunkSizeBytes)
        if (payload.chunkIndex < row.startIndex || payload.chunkIndex >= chunkCount) {
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
                author = fileEnvelope.source,
            )
        ) {
            is FileIngestResult.Staged -> {
                // Progress ACK back to the transfer's pusher (own chunk indices, capped).
                // Best-effort like the transport ACK: untagged, so no outbox outcome.
                ackedCoverage(row, result.contiguousBytes, result.totalBytes, fileEnvelope)
            }

            is FileIngestResult.Ready -> {
                ackedCoverage(row, result.contiguousBytes, result.totalBytes, fileEnvelope)
            }

            is FileIngestResult.Duplicate -> {
                // A duplicate also answers with a coverage ACK: the sender's window only
                // advances on ACKs, and a duplicate is proof the bytes landed.
                ackedCoverage(row, result.contiguousBytes, result.totalBytes, fileEnvelope)
            }

            FileIngestResult.Defer -> {
                AppLog.debug(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_CHUNK_DEFERRED,
                    message = "File chunk deferred: staging bound or transient failure",
                    fields = mapOf("transferId" to fileEnvelope.transferId),
                )
                InboundHandleResult.Deferred()
            }

            FileIngestResult.Refused ->
                InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
    }

    suspend fun handleAck(payload: FilePayload.Ack, fileEnvelope: FileEnvelope) {
        signals.coverageAck(
            fileEnvelope.transferId,
            fileEnvelope.source,
            payload.contiguousChunks,
            payload.missingRanges
        )
    }

    /** Coverage ACK back to the transfer's pusher, in its own chunk indices. */
    private suspend fun ackedCoverage(
        row: FileIncomingTransfer,
        contiguousBytes: Long,
        totalBytes: Long,
        fileEnvelope: FileEnvelope,
    ): InboundHandleResult {
        val contiguous = coverageAckFor(contiguousBytes, totalBytes, row.chunkSizeBytes)
        val reply = buildFileControlEnvelope(
            protection = ctx.envelopeProtectionService,
            transferId = fileEnvelope.transferId,
            source = ctx.localDeviceId,
            target = fileEnvelope.source,
            createdAt = ctx.clock.now(),
            expiresAt = ctx.clock.now() + ctx.routerConfig.value.fileTransferTtl,
            payload = FilePayload.Ack(contiguous, emptyList()),
        )
        return InboundHandleResult.Success(
            sideEffects = listOf(InboundSideEffect.EnqueueDirect(reply)),
            sourceAuthenticated = true,
        )
    }

    suspend fun handleComplete(fileEnvelope: FileEnvelope) {
        signals.complete(fileEnvelope.transferId, fileEnvelope.source)
    }

    suspend fun handleCancel(fileEnvelope: FileEnvelope, payload: FilePayload.Cancel): InboundHandleResult {
        val row = incoming.find(fileEnvelope.transferId)
        if (row == null) {
            signals.cancelled(fileEnvelope.transferId, fileEnvelope.source, payload.reason)
            return InboundHandleResult.Success(sourceAuthenticated = true)
        }
        // Only the transfer's author may end its incoming row; anyone else is ignored
        // (still ACKed) so a third party can never kill someone else's download.
        if (fileEnvelope.source != row.authorDevice) {
            return InboundHandleResult.Success(sourceAuthenticated = true)
        }
        incoming.end(fileEnvelope.transferId)
        return InboundHandleResult.Success(sourceAuthenticated = true)
    }

    companion object {
        /**
         * Coverage in the transfer's own chunk indices (design §3.2, §8): chunk `k` is covered
         * iff `(k + 1) * chunkSize <= P` or `P == totalBytes`. A duplicate also answers with it.
         */
        fun coverageAckFor(contiguousBytes: Long, totalBytes: Long, chunkSizeBytes: Int): Int {
            val count = fileChunkCount(totalBytes, chunkSizeBytes)
            if (count == 0) return -1
            if (contiguousBytes >= totalBytes) return count - 1
            if (contiguousBytes <= 0) return -1
            return (contiguousBytes / chunkSizeBytes).toInt() - 1
        }
    }
}
