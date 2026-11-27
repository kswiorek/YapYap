package org.yapyap.routing.file.inbound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.LogLevel
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FilePackets
import org.yapyap.routing.file.FilePullProcessor
import org.yapyap.routing.file.IncomingHop
import org.yapyap.routing.file.fileGuarded
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
    private val incomingStore: FileIncomingStore,
    private val ingest: FileIngestPort,
    private val requests: FileRequestSource,
    private val pullProcessor: FilePullProcessor,
    private val outbox: OutboxProcessor,
) : ChunkSink {
    suspend fun offer(env: BinaryEnvelope, header: FileHeader.Offer): InboundHandleResult {
        incomingStore.findTransfer(header.transferId)?.let { existing ->
            // The sender re-sends its OFFER until our ACK lands; only it may.
            return if (existing.sender == env.source) InboundHandleResult.Success() else declined()
        }
        // Admission before the seal is opened, so a refused OFFER never spends a ratchet message:
        // the receiver enforces its own small-file limit (a large file is pulled, never pushed).
        val shape = header.shape
        val admissible = shape.isAcceptable() &&
                shape.byteTo <= ctx.routerConfig.value.smallFileMaxBytes &&
                env.content.size in 1..ctx.fileLimits.value.maxSealBytes
        if (!admissible) return declined()
        val seal = when (val lookup = sealOf(env, header)) {
            is SealLookup.Found -> lookup.seal
            is SealLookup.Refused -> return lookup.result
        }
        // Transfers always run to the end of the file; any other range is not one we can place.
        if (shape.byteTo != seal.seal.totalBytes) return declined()
        val manifest = seal.manifest()
        return when (val verdict = ingest.checkOffer(manifest)) {
            FileOfferVerdict.Accept -> accept(env, header, seal, manifest)
            FileOfferVerdict.AlreadyReady -> alreadyReady(env, header, seal, manifest)
            is FileOfferVerdict.Refuse -> {
                AppLog.info(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "File transfer refused",
                    fields = mapOf("transferId" to header.transferId, "reason" to verdict.reason),
                )
                declined()
            }

            FileOfferVerdict.Defer -> InboundHandleResult.Deferred()
        }
    }

    /** Records the transfer and answers with what we already hold, so the sender skips it at once. */
    private suspend fun accept(
        env: BinaryEnvelope,
        header: FileHeader.Offer,
        seal: IncomingSeal,
        manifest: FileManifest,
    ): InboundHandleResult {
        incomingStore.insertTransfer(
            IncomingTransfer(
                transferId = header.transferId,
                sealId = seal.sealId,
                sender = env.source,
                shape = header.shape,
                expiresAt = env.expiresAt,
            ),
        )
        pullProcessor.onOfferReceived(manifest.fileId)
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_OFFER_ACCEPTED,
            message = "File transfer accepted",
            fields = mapOf("transferId" to header.transferId, "fileId" to manifest.fileId),
        )
        val contiguous = requests.heldCoverage(manifest.fileId)?.contiguousBytes ?: 0L
        val index = header.shape.highestContiguousIndex(contiguous)
        if (index < 0) return InboundHandleResult.Success()
        val ack = FileHeader.Ack(header.transferId, index, emptyList())
        return InboundHandleResult.Success(listOf(reply(env.source, ack, env.expiresAt)))
    }

    /** Held already: the hop ends at once (everything covered) and the seal's origin learns it. */
    private suspend fun alreadyReady(
        env: BinaryEnvelope,
        header: FileHeader.Offer,
        seal: IncomingSeal,
        manifest: FileManifest,
    ): InboundHandleResult {
        pullProcessor.onOfferReceived(manifest.fileId)
        val ack = FileHeader.Ack(header.transferId, header.shape.chunkCount - 1, emptyList())
        return InboundHandleResult.Success(
            listOf(
                reply(env.source, ack, env.expiresAt),
                reply(seal.origin, FileHeader.Complete(seal.sealId), env.expiresAt),
            ),
        )
    }

    /**
     * The seal [env] carries: recognised by its id, or opened now and stored at once. Refused when
     * a stored seal of that id came from another origin or when it can't be opened.
     */
    private suspend fun sealOf(env: BinaryEnvelope, header: FileHeader.Offer): SealLookup {
        val sealId = ctx.envelopeProtections.hop.contentId(env.content)
        incomingStore.findSeal(sealId)?.let { stored ->
            return if (stored.origin == header.origin) SealLookup.Found(stored) else SealLookup.Refused(declined())
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
        incomingStore.insertSeal(stored)
        return SealLookup.Found(stored)
    }

    private fun IncomingSeal.manifest() = FileManifest(
        fileId = seal.fileId,
        totalBytes = seal.totalBytes,
        blockSize = seal.blockSize,
        blockHashes = seal.blockHashes,
        fileName = seal.fileName,
        mimeType = seal.mimeType,
    )

    private sealed interface SealLookup {
        class Found(val seal: IncomingSeal) : SealLookup
        class Refused(val result: InboundHandleResult) : SealLookup
    }

    override suspend fun find(transferId: TransferId): IncomingHop? =
        incomingStore.findTransfer(transferId)
            ?.takeIf { it.expiresAt > ctx.clock.now() }
            ?.let { IncomingHop(it.transferId, it.sender, it.shape) }

    override suspend fun accept(hop: IncomingHop, index: Int, ciphertext: ByteArray): InboundHandleResult {
        val transfer =
            incomingStore.findTransfer(hop.transferId) ?: return declined()
        val seal = incomingStore.findSeal(transfer.sealId) ?: return declined()
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

            FileIngestResult.Refused -> return declined()
        }
        val ack = FileHeader.Ack(hop.transferId, hop.shape.highestContiguousIndex(contiguous), emptyList())
        return InboundHandleResult.Success(listOf(reply(hop.sender, ack, transfer.expiresAt)))
    }

    /** The sender ended its transfer: drop it. False when [transferId] is no transfer of ours. */
    suspend fun cancel(transferId: TransferId, from: PeerId): Boolean {
        val transfer = incomingStore.findTransfer(transferId) ?: return false
        if (transfer.sender == from) incomingStore.deleteTransfer(transferId)
        return true
    }

    /**
     * The receive of [fileId] ended. Ready: every live transfer's sender learns it holds
     * everything and every seal's origin gets a COMPLETE. Cancelled: the live transfers are
     * CANCELled. Each answer stands alone — they are politeness, bounded by the TTL.
     */
    suspend fun endReceive(fileId: FileId, end: FileReceiveEnd) {
        for (transfer in incomingStore.transfersByFile(fileId)) {
            val header = when (end) {
                FileReceiveEnd.Ready -> FileHeader.Ack(transfer.transferId, transfer.shape.chunkCount - 1, emptyList())
                is FileReceiveEnd.Cancelled -> FileHeader.Cancel(transfer.transferId, end.reason)
            }
            send(transfer.sender, header, transfer.expiresAt)
            incomingStore.deleteTransfer(transfer.transferId)
        }
        if (end == FileReceiveEnd.Ready) {
            for (seal in incomingStore.sealsByFile(fileId)) {
                send(seal.origin, FileHeader.Complete(seal.sealId), seal.expiresAt)
            }
        }
    }

    /** Drops seals and transfers past their expiry. */
    fun runIn(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            fileGuarded("Incoming file sweep failed") { incomingStore.pruneExpired(ctx.clock.now()) }
            delay(ctx.routerConfig.value.fileSweepInterval)
        }
    }

    private fun declined() = InboundHandleResult.Rejected(PacketNackReason.DECLINED)

    private suspend fun reply(peer: PeerId, header: FileHeader, expiresAt: Instant) =
        InboundSideEffect.EnqueueDirect(packets.control(peer, header, expiresAt))

    private suspend fun send(peer: PeerId, header: FileHeader, expiresAt: Instant) {
        fileGuarded(
            "Could not answer a file transfer; it ends on its TTL",
            mapOf("peer" to peer),
            level = LogLevel.WARN,
        ) {
            outbox.enqueueAndWake(packets.control(peer, header, expiresAt), ctx.clock.now())
        }
    }
}
