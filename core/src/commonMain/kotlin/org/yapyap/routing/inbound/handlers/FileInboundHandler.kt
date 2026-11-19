package org.yapyap.routing.inbound.handlers

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.FileId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.ChunkRange
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FileCarrier
import org.yapyap.routing.file.FileEvent
import org.yapyap.routing.file.FileIngestPort
import org.yapyap.routing.file.FileIngestResult
import org.yapyap.routing.file.FileOfferInfo
import org.yapyap.routing.file.FileOfferVerdict
import org.yapyap.routing.file.FilePushOps
import org.yapyap.routing.file.buildFileControlEnvelope
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingStatus
import org.yapyap.routing.store.FileIncomingTransfer
import org.yapyap.routing.store.FileIncomingTransferStore
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileRelayTransfer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Real small-path inbound handler (step 11 local target; step 18 relay door).
 *
 * Local target: the OFFER/CHUNK/REQUEST serve path. Foreign target: this device is a
 * relay holding a deposit (design §7) — signature-checked at the door, stored as
 * ciphertext, forwarded only when whole. The relay path never touches the ingest
 * port, the pusher or the carrier: routing-only, no orchestrator leak.
 *
 * Chunk discipline (design §3.5): a CHUNK for an unknown or unprocessed `transferId` is
 * never ACKed and never stored — at a target or at a relay — answered DECLINED.
 */
internal class FileInboundHandler(
    private val ctx: RoutingContext,
    private val incoming: FileIncomingTransferStore,
    private val ingest: FileIngestPort,
    private val carrier: FileCarrier,
    private val pusher: FilePushOps,
    private val relay: FileRelayStore,
    private val onCoverageAck: suspend (transferId: TransferId, contiguousChunks: Int, missing: List<ChunkRange>) -> Unit,
    private val onTransferComplete: suspend (transferId: TransferId) -> Unit,
    private val onTransferCancelled: suspend (transferId: TransferId) -> Unit,
    private val onOfferReceived: suspend (fileId: FileId) -> Unit,
    /** The door stored a deposit chunk/OFFER: the relay forwarder may have work. */
    private val onRelayStored: () -> Unit,
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
            return handleRelay(env, fileEnvelope)
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

    // -- Relay door (design §7.1–§7.2, step 18) ---------------------------------------
    //
    // A foreign inner target means this device is a relay for the transfer: verify the
    // original sender's signature, store the ciphertext, forward only when whole.
    // DECLINED for a dead transferId or an out-of-range index so the sender cleans up
    // at once instead of retrying into a dead store. Never touches the ingest port,
    // the pusher (authoring side) or the carrier — the relay path is routing-only.

    private suspend fun handleRelay(env: BinaryEnvelope, fileEnvelope: FileEnvelope): InboundHandleResult {
        val opened = try {
            ctx.envelopeProtectionService.openFile(fileEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open relayed file envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        // Endpoint healing (and arrival-link adoption) key off the outer source: only a
        // deposit straight from its author authenticates that peer's reachability. Traffic
        // in transit proves nothing about the claimed outer source (message-transit rule).
        val direct = env.source == fileEnvelope.source
        return when (val payload = opened.payload) {
            is FilePayload.Offer -> handleRelayOffer(env, fileEnvelope, payload, direct)
            is FilePayload.EncryptedChunk -> handleRelayChunk(env, fileEnvelope, payload, direct)
            is FilePayload.Cancel -> {
                // Polite teardown of a deposit (the author cancelling its outstanding
                // deposits): drop what we hold; always ACK so the sender stops re-sending.
                relay.delete(fileEnvelope.transferId)
                InboundHandleResult.Success(sourceAuthenticated = direct)
            }
            // ACK / COMPLETE / REQUEST never address a relay (design §7): the transfer is
            // gone here — answer DECLINED like any unknown transferId (§3.6).
            else -> InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
    }

    private suspend fun handleRelayOffer(
        env: BinaryEnvelope,
        fileEnvelope: FileEnvelope,
        payload: FilePayload.Offer,
        direct: Boolean,
    ): InboundHandleResult {
        val transferId = fileEnvelope.transferId
        val now = ctx.clock.now()
        relay.find(transferId)?.let { existing ->
            if (now < existing.expiresAt) {
                // Duplicate OFFER (the sender retries until our ACK lands): already
                // stored, just ACK so it stops.
                return InboundHandleResult.Success(sourceAuthenticated = direct)
            }
            relay.delete(transferId)
        }
        val chunkSize = payload.chunkSizeBytes
        val totalBytes = payload.totalBytes
        // Relays hold small files only (§5): large files never touch relays.
        if (chunkSize <= 0 || totalBytes < 0 || totalBytes > SMALL_FILE_MAX_BYTES) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val fileCount = fileCount(totalBytes, chunkSize)
        if (payload.startIndex < 0 || payload.startIndex > fileCount) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val reserved = maxOf(0L, totalBytes - payload.startIndex.toLong() * chunkSize)
        if (!admit(transferId, reserved)) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        relay.insert(
            FileRelayTransfer(
                transferId = transferId,
                sourceDevice = fileEnvelope.source,
                targetDevice = fileEnvelope.target,
                chunkSizeBytes = chunkSize,
                totalBytes = totalBytes,
                startIndex = payload.startIndex,
                // Verbatim inner bytes (author signature intact) — replayed on forward.
                offerPayload = env.payload,
                status = if (fileCount - payload.startIndex <= 0) FileRelayStatus.WHOLE else FileRelayStatus.RESERVED,
                reservedBytes = reserved,
                markerPacketId = null,
                createdAt = now,
                lastChunkAt = now,
                expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
            ),
        )
        onRelayStored()
        return InboundHandleResult.Success(sourceAuthenticated = direct)
    }

    private suspend fun handleRelayChunk(
        env: BinaryEnvelope,
        fileEnvelope: FileEnvelope,
        payload: FilePayload.EncryptedChunk,
        direct: Boolean,
    ): InboundHandleResult {
        val transferId = fileEnvelope.transferId
        val now = ctx.clock.now()
        val row = relay.find(transferId)
        if (row == null || now >= row.expiresAt) {
            if (row != null) relay.delete(transferId)
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val fileCount = fileCount(row.totalBytes, row.chunkSizeBytes)
        if (payload.chunkIndex < row.startIndex || payload.chunkIndex >= fileCount) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        // PK dedup: a re-deposited chunk after a stall is a no-op (design §7.2).
        relay.insertChunk(transferId, payload.chunkIndex, env.payload)
        relay.touch(transferId, now)
        if (row.status == FileRelayStatus.RESERVED &&
            relay.chunkCount(transferId) >= maxOf(0, fileCount - row.startIndex)
        ) {
            relay.setStatus(transferId, FileRelayStatus.WHOLE, now)
        }
        onRelayStored()
        return InboundHandleResult.Success(sourceAuthenticated = direct)
    }

    /**
     * Admission against `relayStoragePledgeBytes`: evict whole transfers oldest-first
     * until the reservation fits (RESERVED transfers mid-deposit are not exempt — an
     * evicted sender learns it through the door DECLINED on its next chunk, §7.2).
     * False when nothing fits: the sender is DECLINED at the door and tries the next
     * relay or re-offers later.
     */
    private suspend fun admit(transferId: TransferId, reserved: Long): Boolean {
        val pledge = ctx.routerConfig.value.relayStoragePledgeBytes
        var used = relay.reservedBytes()
        if (used + reserved <= pledge) return true
        var candidates = relay.evictionCandidates(16)
        while (used + reserved > pledge && candidates.isNotEmpty()) {
            for (candidate in candidates) {
                if (candidate == transferId || used + reserved <= pledge) continue
                relay.find(candidate)?.let { victim ->
                    relay.delete(candidate)
                    used -= victim.reservedBytes
                }
            }
            candidates = if (used + reserved > pledge) relay.evictionCandidates(16) else emptyList()
        }
        return used + reserved <= pledge
    }

    private fun fileCount(totalBytes: Long, chunkSizeBytes: Int): Int =
        if (totalBytes == 0L) 0
        else ((totalBytes - 1) / chunkSizeBytes + 1).toInt()
}
