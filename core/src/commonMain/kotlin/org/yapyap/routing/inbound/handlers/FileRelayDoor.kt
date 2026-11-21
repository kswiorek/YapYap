package org.yapyap.routing.inbound.handlers

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.buildFileControlEnvelope
import org.yapyap.routing.file.fileChunkCount
import org.yapyap.routing.file.isValidOfferPublicHalf
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileRelayTransfer
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.PacketOutbox
import kotlin.coroutines.cancellation.CancellationException

/**
 * Relay door (design §7.1): admission and storage for envelopes whose inner target is another
 * device. Verifies the original sender's signature (via `openFile`), checks the inner source is
 * a known non-banned device, stores ciphertext verbatim, and forwards only when whole. Never
 * touches the ingest port or the pusher — routing-only, no orchestrator leak.
 *
 * DECLINED for a dead `transferId` or an out-of-range index so the sender cleans up at once
 * instead of retrying into a dead store. Each stored chunk is answered with a coverage ACK
 * built from the chunks stored so far, so the depositor's window advances.
 */
internal class FileRelayDoor(
    private val ctx: RoutingContext,
    private val protection: FileEnvelopeProtection,
    private val relay: FileRelayStore,
    private val packetOutbox: PacketOutbox,
    /** [TransferId]'s relay row became WHOLE: the push engine starts its forward. */
    private val onRelayWhole: suspend (TransferId) -> Unit,
) {
    suspend fun handleRelay(env: BinaryEnvelope, fileEnvelope: FileEnvelope): InboundHandleResult {
        val opened = try {
            protection.open(fileEnvelope)
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
                // Polite teardown of a deposit: drop what we hold only when the CANCEL comes
                // from the depositor (the transfer's source device); otherwise ignore it and
                // still ACK so the sender stops re-sending.
                val row = relay.find(fileEnvelope.transferId)
                if (row != null && fileEnvelope.source != row.sourceDevice) {
                    AppLog.debug(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Ignoring relay CANCEL from non-depositor",
                        fields = mapOf("transferId" to fileEnvelope.transferId, "from" to fileEnvelope.source),
                    )
                    return InboundHandleResult.Success(sourceAuthenticated = direct)
                }
                dropTransfer(fileEnvelope.transferId)
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
            dropTransfer(transferId)
        }
        // Inner source check (design §7.1): the inner source must be a known device and not
        // banned; otherwise anyone learning a transferId could stuff the store.
        if (!isKnownNonBanned(fileEnvelope.source)) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        // Relays hold small files only (§5): large files never touch relays.
        if (!isValidOfferPublicHalf(payload)) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val chunkSize = payload.chunkSizeBytes
        val totalBytes = payload.totalBytes
        val count = fileChunkCount(totalBytes, chunkSize)
        val reserved = maxOf(0L, totalBytes - payload.startIndex.toLong() * chunkSize)
        val row = FileRelayTransfer(
            transferId = transferId,
            sourceDevice = fileEnvelope.source,
            targetDevice = fileEnvelope.target,
            chunkSizeBytes = chunkSize,
            totalBytes = totalBytes,
            startIndex = payload.startIndex,
            // Verbatim inner bytes (author signature intact) — replayed on forward.
            offerPayload = env.payload,
            status = if (count - payload.startIndex <= 0) FileRelayStatus.WHOLE else FileRelayStatus.RESERVED,
            reservedBytes = reserved,
            markerPacketId = null,
            createdAt = now,
            lastChunkAt = now,
            expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
        )
        // One transaction per state change: evict + insert atomically; evicted rows' outbox
        // rows are deleted right after the commit (relay rows first, then outbox rows — a
        // crash in between leaves outbox rows whose outcome the engine ignores).
        val result = relay.admit(row, reserved, ctx.routerConfig.value.fileRelayBudgetBytes)
        for (evicted in result.evicted) {
            packetOutbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_FORWARD, evicted.value))
        }
        if (!result.admitted) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        // An empty suffix is WHOLE at admission: forward it now.
        if (row.status == FileRelayStatus.WHOLE) onRelayWhole(transferId)
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
            if (row != null) dropTransfer(transferId)
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        // Inner source check: only the depositor stores chunks here.
        if (fileEnvelope.source != row.sourceDevice) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val count = fileChunkCount(row.totalBytes, row.chunkSizeBytes)
        if (payload.chunkIndex < row.startIndex || payload.chunkIndex >= count) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        // PK dedup: a re-deposited chunk after a stall is a no-op (design §7.2).
        // Stored, touched and promoted in one store transaction.
        // Null: evicted or dropped since the read above; the deposit ends like any dead transfer.
        val status = relay.storeChunk(transferId, payload.chunkIndex, env.payload, now)
            ?: return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        if (status == FileRelayStatus.WHOLE) onRelayWhole(transferId)
        // Coverage ACK from the chunks stored so far (same rule as the target): the
        // depositor's window advances on ACKs; silence would stall it until the TTL.
        val contiguous = storedContiguous(transferId, row.startIndex)
        val reply = buildFileControlEnvelope(
            protection = protection,
            transferId = transferId,
            source = ctx.localDeviceId,
            target = fileEnvelope.source,
            createdAt = now,
            expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
            payload = FilePayload.Ack(contiguous, emptyList()),
        )
        return InboundHandleResult.Success(
            sideEffects = listOf(InboundSideEffect.EnqueueDirect(reply)),
            sourceAuthenticated = direct,
        )
    }

    private suspend fun storedContiguous(transferId: TransferId, startIndex: Int): Int {
        val stored = relay.chunkRefs(transferId).mapTo(HashSet()) { it.chunkIndex }
        var contiguous = startIndex - 1
        while (stored.contains(contiguous + 1)) contiguous += 1
        return contiguous
    }

    /** Known device whose own status and account status are both not BANNED (design §7.1). */
    private suspend fun isKnownNonBanned(device: PeerId): Boolean {
        val account = ctx.devices.getAccountIdForDevice(device)
        val deviceStatus = ctx.devices.getDeviceStatus(device)
        if (account == null && deviceStatus == null) return false
        if (deviceStatus == IdentityStatus.BANNED) return false
        return account == null || ctx.devices.getAccountStatus(account) != IdentityStatus.BANNED
    }

    /** Relay rows first, then outbox rows (documented order; a crash in between leaves outbox
     *  rows whose outcome the engine ignores because no forward actor exists). */
    private suspend fun dropTransfer(transferId: TransferId) {
        relay.delete(transferId)
        packetOutbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value))
    }
}
