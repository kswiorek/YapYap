package org.yapyap.routing.file.inbound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.FileId
import org.yapyap.protocol.MAX_SEAL_BYTES
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FilePackets
import org.yapyap.routing.file.FilePullProcessor
import org.yapyap.routing.file.IncomingHop
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FileReceiveEnd
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingStore
import org.yapyap.routing.store.IncomingSeal
import org.yapyap.routing.store.IncomingTransfer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The final target's side of the small path (docs/envelope-redesign.md §5.5): seals and the
 * transfers delivering them, the orchestrator's receive engine behind.
 *
 * A seal is opened once: its row is written the moment it opens, so the same seal arriving by
 * another hop (the direct push, a relay's forward) is recognised by its id and never reaches the
 * ratchet again. A seal that fails as already opened with no row is the accepted crash edge: that
 * hop is refused and the REQUEST path brings the file back with a fresh seal.
 */
internal class TargetSide(
    private val ctx: RoutingContext,
    private val packets: FilePackets,
    private val incoming: FileIncomingStore,
    private val ingest: FileIngestPort,
    private val requests: FileRequestSource,
    private val pullProcessor: FilePullProcessor,
    private val outbox: OutboxProcessor,
) : ChunkSink {
    suspend fun offer(env: BinaryEnvelope, header: FileHeader.Offer): InboundHandleResult {
        incoming.findTransfer(header.transferId)?.let { existing ->
            // The sender re-sends its OFFER until our ACK lands; only it may.
            return if (existing.sender == env.source) InboundHandleResult.Success()
            else InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        if (!header.shape.isAcceptable() || env.content.size !in 1..MAX_SEAL_BYTES) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val seal = when (val lookup = sealOf(env, header)) {
            is SealLookup.Found -> lookup.seal
            is SealLookup.Refused -> return lookup.result
        }
        val shape = header.shape
        // Transfers always run to the end of the file; any other range is not one we can place.
        if (shape.byteTo != seal.seal.totalBytes) return InboundHandleResult.Rejected(PacketNackReason.DECLINED)

        val manifest = FileManifest(
            fileId = seal.seal.fileId,
            totalBytes = seal.seal.totalBytes,
            blockSize = seal.seal.blockSize,
            blockHashes = seal.seal.blockHashes,
            fileName = seal.seal.fileName,
            mimeType = seal.seal.mimeType,
        )
        return when (val verdict = ingest.checkOffer(manifest)) {
            FileOfferVerdict.Accept -> {
                incoming.insertTransfer(
                    IncomingTransfer(
                        header.transferId,
                        seal.sealId,
                        env.source,
                        shape,
                        env.expiresAt
                    )
                )
                pullProcessor.onOfferReceived(manifest.fileId)
                AppLog.info(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_OFFER_ACCEPTED,
                    message = "File transfer accepted",
                    fields = mapOf("transferId" to header.transferId, "fileId" to manifest.fileId),
                )
                // What we already hold lets the sender skip it at once.
                val contiguous = requests.heldCoverage(manifest.fileId)?.contiguousBytes ?: 0L
                val index = shape.highestContiguousIndex(contiguous)
                InboundHandleResult.Success(
                    if (index < 0) emptyList()
                    else listOf(
                        reply(
                            env.source,
                            FileHeader.Ack(header.transferId, index, emptyList()),
                            env.expiresAt
                        )
                    ),
                )
            }

            // Held already: the hop ends at once (everything covered) and the origin learns it.
            FileOfferVerdict.AlreadyReady -> {
                pullProcessor.onOfferReceived(manifest.fileId)
                InboundHandleResult.Success(
                    listOf(
                        reply(
                            env.source,
                            FileHeader.Ack(header.transferId, shape.chunkCount - 1, emptyList()),
                            env.expiresAt
                        ),
                        reply(seal.origin, FileHeader.Complete(seal.sealId), env.expiresAt),
                    ),
                )
            }

            is FileOfferVerdict.Refuse -> {
                AppLog.info(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "File transfer refused",
                    fields = mapOf("transferId" to header.transferId, "reason" to verdict.reason),
                )
                InboundHandleResult.Rejected(PacketNackReason.DECLINED)
            }

            FileOfferVerdict.Defer -> InboundHandleResult.Deferred()
        }
    }

    /**
     * The seal [env] carries: recognised by its id, or opened now and stored at once. Refused when
     * a stored seal of that id came from another origin, or when it can't be opened.
     */
    private suspend fun sealOf(env: BinaryEnvelope, header: FileHeader.Offer): SealLookup {
        val sealId = ctx.envelopeProtections.hop.contentId(env.content)
        incoming.findSeal(sealId)?.let { stored ->
            return if (stored.origin == header.origin) SealLookup.Found(stored)
            else SealLookup.Refused(InboundHandleResult.Rejected(PacketNackReason.DECLINED))
        }
        val seal = try {
            ctx.envelopeProtections.file.openSeal(env.content, header.origin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure("Failed to open file seal", env.packetId, env.source, e)
            // Opened before but never stored (the crash edge): this hop can't be served; refuse it.
            return SealLookup.Refused(
                if (e is ProtectionException.Duplicate) InboundHandleResult.Rejected(PacketNackReason.PERMANENT_PROTECTION_FAILED)
                else inboundResultForProtectionFailure(e),
            )
        }
        val stored = IncomingSeal(sealId, header.origin, seal, env.expiresAt)
        incoming.insertSeal(stored)
        return SealLookup.Found(stored)
    }

    private sealed interface SealLookup {
        class Found(val seal: IncomingSeal) : SealLookup
        class Refused(val result: InboundHandleResult) : SealLookup
    }

    override suspend fun find(transferId: TransferId): IncomingHop? =
        incoming.findTransfer(transferId)
            ?.takeIf { it.expiresAt > ctx.clock.now() }
            ?.let { IncomingHop(it.transferId, it.sender, it.shape) }

    override suspend fun accept(hop: IncomingHop, index: Int, ciphertext: ByteArray): InboundHandleResult {
        val transfer =
            incoming.findTransfer(hop.transferId) ?: return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        val seal = incoming.findSeal(transfer.sealId) ?: return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        val offset = hop.shape.chunkOffset(index)
        val plaintext = try {
            ctx.envelopeProtections.file.decryptChunk(seal.sealId, seal.seal.key, offset, ciphertext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            return inboundResultForProtectionFailure(e)
        }
        val contiguous = when (val result = ingest.ingest(seal.seal.fileId, offset, plaintext)) {
            is FileIngestResult.Staged -> result.contiguousBytes
            is FileIngestResult.Ready -> result.contiguousBytes
            is FileIngestResult.Duplicate -> result.contiguousBytes
            FileIngestResult.Defer -> {
                AppLog.debug(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_CHUNK_DEFERRED,
                    message = "File chunk deferred: staging bound or transient failure",
                    fields = mapOf("transferId" to hop.transferId),
                )
                return InboundHandleResult.Deferred()
            }

            FileIngestResult.Refused -> return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val ack = FileHeader.Ack(hop.transferId, hop.shape.highestContiguousIndex(contiguous), emptyList())
        return InboundHandleResult.Success(listOf(reply(hop.sender, ack, transfer.expiresAt)))
    }

    /** The sender ended its transfer: drop it. False when [transferId] is no transfer of ours. */
    suspend fun cancel(transferId: TransferId, from: PeerId): Boolean {
        val transfer = incoming.findTransfer(transferId) ?: return false
        if (transfer.sender == from) incoming.deleteTransfer(transferId)
        return true
    }

    /**
     * The receive of [fileId] ended. Ready: every live transfer's sender learns it holds
     * everything and every seal's origin gets a COMPLETE. Cancelled: the live transfers are
     * CANCELled. Each answer stands alone — they are politeness, bounded by the TTL.
     */
    suspend fun endReceive(fileId: FileId, end: FileReceiveEnd) {
        for (transfer in incoming.transfersByFile(fileId)) {
            val header = when (end) {
                FileReceiveEnd.Ready -> FileHeader.Ack(transfer.transferId, transfer.shape.chunkCount - 1, emptyList())
                is FileReceiveEnd.Cancelled -> FileHeader.Cancel(transfer.transferId, end.reason)
            }
            send(transfer.sender, header, transfer.expiresAt)
            incoming.deleteTransfer(transfer.transferId)
        }
        if (end == FileReceiveEnd.Ready) {
            for (seal in incoming.sealsByFile(fileId)) send(
                seal.origin,
                FileHeader.Complete(seal.sealId),
                seal.expiresAt
            )
        }
    }

    /** Drops seals and transfers past their expiry. */
    fun runIn(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            try {
                incoming.pruneExpired(ctx.clock.now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Incoming file sweep failed",
                    throwable = e,
                )
            }
            delay(SWEEP_INTERVAL)
        }
    }

    private suspend fun reply(peer: PeerId, header: FileHeader, expiresAt: Instant) =
        InboundSideEffect.EnqueueDirect(packets.control(peer, header, expiresAt))

    private suspend fun send(peer: PeerId, header: FileHeader, expiresAt: Instant) {
        try {
            outbox.enqueueAndWake(packets.control(peer, header, expiresAt), ctx.clock.now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Could not answer a file transfer; it ends on its TTL",
                fields = mapOf("peer" to peer, "error" to e.toString()),
            )
        }
    }

    private companion object {
        val SWEEP_INTERVAL = 5.minutes
    }
}
