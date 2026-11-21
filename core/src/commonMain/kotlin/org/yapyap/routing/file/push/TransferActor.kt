package org.yapyap.routing.file.push

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.buildFileControlEnvelope
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePush
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.FilePushStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.RetryClass
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * One coroutine per live `file_pushes` row. Owns its in-memory window; all durable state lives
 * in the row plus tagged outbox rows and `file_push_chunks`.
 *
 * Window counts queued chunk rows (`queued.size < maxInFlightChunks`), never coverage. Coverage
 * only moves `resumeFrom` and never changes the window count.
 */
internal class TransferActor(
    private val ctx: RoutingContext,
    private val protection: FileEnvelopeProtection,
    private val pushes: FilePushStore,
    private val outbox: FileOutbox,
    private val events: MutableSharedFlow<FileEvent>,
    row: FilePush,
    private val source: ChunkSource,
    private val engine: FilePushEngine,
    private val onTerminal: suspend (TransferActor) -> Unit,
    private val onDepositAsk: suspend (TransferActor) -> Unit,
    private val tagKind: OutboxTag.Kind,
    private val depositPolicy: FileDepositPolicy = FileDepositPolicy(pushes),
) {
    val transferId = row.transferId
    val mailbox = Channel<PushInput>(Channel.UNLIMITED)

    private var queued: MutableSet<Int> = mutableSetOf()
    private var nextToQueue: Int = row.nextChunkIndex
    private var resumeFrom: Int = maxOf(row.startIndex, row.contiguousAcked + 1)

    // A fresh actor (also after a restart) starts its stall clock now: a restart is not
    // evidence that the target is gone, so it must not trip the timeout deposit at once.
    private var lastAckAt: Instant = ctx.clock.now()
    private var markerAcked: Boolean = row.status == FilePushStatus.IN_FLIGHT
    private var markerSent: Boolean = row.markerPacketId != null
    private var current: FilePush = row
    private var ended = false

    private fun tag() = OutboxTag(tagKind, transferId.value)

    private fun fileCount(): Int {
        val total = current.totalBytes
        val size = current.chunkSizeBytes
        if (total == 0L) return 0
        return ((total - 1) / size + 1).toInt()
    }

    /** Rebuilds `queued` from `file_push_chunks` + still-queued outbox rows. Never re-seals. */
    suspend fun recover() {
        val byIndex = pushes.chunksByTransfer(transferId)
        if (byIndex.isEmpty()) {
            nextToQueue = resumeFrom
            return
        }
        val stillQueued = outbox.packetIdsByTag(tag()).toSet()
        queued = byIndex.filter { it.value in stillQueued }.keys.toMutableSet()
        // Drop stale mappings for rows the outbox no longer holds.
        for ((_, packetId) in byIndex) {
            if (packetId !in stillQueued) pushes.deleteChunkByPacket(packetId)
        }
        if (nextToQueue < resumeFrom) nextToQueue = resumeFrom
    }

    suspend fun run() {
        try {
            if (current.status == FilePushStatus.CREATED && !markerSent) {
                // Fresh transfer, or a marker lost to DEFER before it ever queued: one marker
                // row out. A queued marker's expiry re-arms through its own Expired outcome,
                // never by stacking another row here.
                sendMarker()
            } else if (markerAcked) {
                pump()
            }
            for (input in mailbox) {
                handle(input)
                if (ended) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Transfer actor failed",
                throwable = e,
                fields = mapOf("transferId" to transferId),
            )
        } finally {
            // Releases the plaintext stream: a cancelled actor never reads to its last chunk.
            source.close()
            try {
                onTerminal(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Transfer actor terminal cleanup failed",
                    throwable = e,
                    fields = mapOf("transferId" to transferId),
                )
            }
        }
    }

    private suspend fun refresh(): Boolean {
        val found = pushes.find(transferId) ?: return false
        current = found
        return true
    }

    private suspend fun handle(input: PushInput) {
        when (input) {
            is PushInput.Outcome -> onOutcome(input.outcome)
            is PushInput.CoverageAck -> onCoverage(input.highestContiguousIndex)
            is PushInput.Complete -> onCompleteSignal()
            is PushInput.Cancelled -> onCancelled(input.reason)
            is PushInput.UserCancel -> onUserCancel()
            is PushInput.SilentCancel -> onSilentCancel()
            is PushInput.LinkClosed -> {
                // Gone-only: a closed link deposits only what is actually in flight.
                if (markerAcked && queued.isNotEmpty()) onDepositAsk(this)
            }

            is PushInput.Tick -> onTick()
        }
    }

    private suspend fun onOutcome(outcome: OutboxOutcome) {
        if (!refresh()) {
            ended = true
            return
        }
        if (!current.status.isLive) {
            ended = true
            return
        }
        when (outcome) {
            is OutboxOutcome.Acked -> {
                touchAck()
                if (outcome.packetId == current.markerPacketId) {
                    if (current.status == FilePushStatus.CREATED) {
                        pushes.setStatus(transferId, FilePushStatus.IN_FLIGHT)
                        refresh()
                    }
                    markerAcked = true
                    pump()
                } else {
                    val mapping = pushes.chunkByPacket(outcome.packetId)
                    if (mapping != null && mapping.first == transferId) {
                        queued.remove(mapping.second)
                        pushes.deleteChunkByPacket(outcome.packetId)
                    }
                    pump()
                }
            }

            is OutboxOutcome.Nacked -> {
                if (outcome.reason == PacketNackReason.DECLINED) {
                    if (outcome.packetId == current.markerPacketId) {
                        if (current.kind == FilePushKind.DEPOSIT) {
                            engine.terminateSilent(transferId, FilePushStatus.ENDED)
                        } else {
                            engine.failTransfer(
                                transferId,
                                FilePushStatus.DECLINED,
                                FileFailureReason.DECLINED,
                            )
                        }
                        ended = true
                    } else {
                        val mapping = pushes.chunkByPacket(outcome.packetId)
                        if (mapping != null && mapping.first == transferId) {
                            queued.remove(mapping.second)
                            pushes.deleteChunkByPacket(outcome.packetId)
                        }
                        // The transfer is gone at the other end; quietly ENDED, never DECLINED.
                        if (current.kind == FilePushKind.DEPOSIT) {
                            engine.terminateSilent(transferId, FilePushStatus.ENDED)
                        } else {
                            engine.failTransfer(transferId, FilePushStatus.ENDED, FileFailureReason.ENDED)
                        }
                        ended = true
                    }
                } else {
                    if (current.kind == FilePushKind.DEPOSIT) {
                        engine.terminateSilent(transferId, FilePushStatus.ENDED)
                    } else {
                        engine.failTransfer(transferId, FilePushStatus.ENDED, FileFailureReason.ENDED)
                    }
                    ended = true
                }
            }

            is OutboxOutcome.Expired -> {
                if (ctx.clock.now() >= current.expiresAt) {
                    if (current.kind == FilePushKind.DEPOSIT) {
                        engine.terminateSilent(transferId, FilePushStatus.EXPIRED)
                    } else {
                        engine.failTransfer(transferId, FilePushStatus.EXPIRED, FileFailureReason.EXPIRED)
                    }
                    ended = true
                } else {
                    if (outcome.packetId == current.markerPacketId) {
                        sendMarker()
                    } else {
                        val mapping = pushes.chunkByPacket(outcome.packetId)
                        if (mapping != null && mapping.first == transferId) {
                            queued.remove(mapping.second)
                            pushes.deleteChunkByPacket(outcome.packetId)
                            if (mapping.second >= resumeFrom) {
                                nextToQueue = minOf(nextToQueue, mapping.second)
                                persistProgress()
                            }
                        }
                        pump()
                    }
                }
            }
        }
    }

    private suspend fun onCoverage(contiguous: Int) {
        if (!refresh()) {
            ended = true
            return
        }
        if (!current.status.isLive) {
            ended = true
            return
        }
        if (contiguous > current.contiguousAcked) {
            // Coverage only moves the resume point and persists it; it never changes the
            // window count — queued rows drain on their own outcomes (§6.2).
            resumeFrom = maxOf(resumeFrom, contiguous + 1)
            pushes.setProgress(transferId, nextToQueue, contiguous)
            refresh()
            touchAck()
            emitProgress()
            pump()
        }
    }

    /**
     * Progress for the account, not the transfer: the minimum covered bytes across the
     * account's live device transfers (design §12). A lagging device drags the number
     * down; it recovers as that device catches up.
     */
    private suspend fun emitProgress() {
        if (current.kind == FilePushKind.DEPOSIT) return
        val account = accountOf() ?: return
        val rows = pushes.allByFile(current.fileId).filter {
            it.kind != FilePushKind.DEPOSIT && it.status.isLive && accountOf(it.targetDevice) == account
        }
        val sent = rows.minOfOrNull { coveredBytes(it) } ?: return
        events.emit(FileEvent.OutgoingProgress(current.fileId, account, sent, current.totalBytes))
    }

    private fun coveredBytes(row: FilePush): Long =
        minOf((row.contiguousAcked.toLong() + 1) * row.chunkSizeBytes, row.totalBytes).coerceAtLeast(0L)

    private suspend fun onCompleteSignal() {
        engine.completeTransfer(transferId)
        ended = true
    }

    private suspend fun onCancelled(reason: FileCancelReason) {
        when (reason) {
            FileCancelReason.DECLINED,
            FileCancelReason.NO_SPACE,
                -> engine.failTransfer(transferId, FilePushStatus.DECLINED, FileFailureReason.DECLINED)

            FileCancelReason.ABORT ->
                engine.failTransfer(transferId, FilePushStatus.ENDED, FileFailureReason.ENDED)
        }
        ended = true
    }

    private suspend fun onUserCancel() {
        if (!refresh()) {
            ended = true
            return
        }
        if (!current.status.isLive) {
            ended = true
            return
        }
        sendCancel()
        if (current.kind == FilePushKind.DEPOSIT) {
            engine.terminateSilent(transferId, FilePushStatus.CANCELLED)
        } else {
            engine.failTransfer(transferId, FilePushStatus.CANCELLED, FileFailureReason.CANCELLED)
        }
        ended = true
    }

    /** Supersede (serve-with-supersede): CANCEL the old transfer, drop its rows, stay silent. */
    private suspend fun onSilentCancel() {
        if (!refresh()) {
            ended = true
            return
        }
        if (!current.status.isLive) {
            ended = true
            return
        }
        sendCancel()
        engine.terminateSilent(transferId, FilePushStatus.CANCELLED)
        ended = true
    }

    private suspend fun onTick() {
        if (!refresh()) {
            ended = true
            return
        }
        val now = ctx.clock.now()
        if (!current.status.isLive) {
            ended = true
            return
        }
        if (now >= current.expiresAt) {
            if (current.kind == FilePushKind.DEPOSIT) {
                engine.terminateSilent(transferId, FilePushStatus.EXPIRED)
            } else {
                engine.failTransfer(transferId, FilePushStatus.EXPIRED, FileFailureReason.EXPIRED)
            }
            ended = true
            return
        }
        // A marker lost to DEFER is re-armed here, never left to stall until the TTL.
        // Only when this actor never got a marker out: expiry of a queued marker is
        // re-armed through its own Expired outcome instead, so one marker row never
        // multiplies into many.
        if (!markerAcked && !markerSent && current.status == FilePushStatus.CREATED) {
            sendMarker()
        }
        // Deferred seal re-arm lives on the actor's own tick, not on the next push.
        pump()
        // Gone-only stall check while chunks are queued.
        if (depositPolicy.shouldDeposit(
                row = current,
                now = now,
                lastAckAt = lastAckAt,
                hasQueued = queued.isNotEmpty(),
                markerAcked = markerAcked,
                timeout = ctx.routerConfig.value.chunkAckTimeout,
            )
        ) {
            onDepositAsk(this)
        }
    }

    private suspend fun pump() {
        if (!refresh() || !current.status.isLive) return
        if (!markerAcked) return
        val window = ctx.routerConfig.value.maxInFlightChunks
        val count = fileCount()
        if (count in 1..resumeFrom) return
        if (nextToQueue < resumeFrom) {
            nextToQueue = resumeFrom
            persistProgress()
            refresh()
        }
        while (queued.size < window && nextToQueue < count) {
            val index = nextToQueue
            // Still queued (a re-queue after an early expiry walked back past it): skip it,
            // never seal and enqueue the same index twice.
            if (index in queued) {
                nextToQueue += 1
                continue
            }
            when (val result = buildChunk(index)) {
                is ChunkResult.Envelope -> {
                    val packetId = result.envelope.packetId
                    // Chunks never back up: only the authoring marker is message-backed.
                    outbox.enqueue(result.envelope, tag(), RelayBackup.NONE, RetryClass.FLAT)
                    pushes.recordChunk(transferId, index, packetId)
                    queued.add(index)
                    nextToQueue += 1
                    persistProgress()
                    refresh()
                }

                is ChunkResult.Deferred -> return
                is ChunkResult.SourceLost -> {
                    if (current.kind == FilePushKind.DEPOSIT) {
                        engine.terminateSilent(transferId, FilePushStatus.ENDED)
                    } else {
                        engine.failTransfer(transferId, FilePushStatus.ENDED, FileFailureReason.ENDED)
                    }
                    ended = true
                    return
                }
            }
        }
    }

    private suspend fun buildChunk(index: Int): ChunkResult {
        return try {
            source.chunk(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            if (e.disposition == ProtectionDisposition.DEFER) {
                ChunkResult.Deferred
            } else {
                ChunkResult.SourceLost
            }
        }
    }

    private suspend fun sendMarker() {
        if (!refresh() || !current.status.isLive) return
        // An OFFER is never re-sent under the same transferId for progress; this only
        // (re)arms the CREATED marker or a marker lost to expiry.
        val envelope = try {
            source.offer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            if (e.disposition == ProtectionDisposition.DEFER) {
                AppLog.warn(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "File OFFER deferred: session not ready; re-armed later",
                    fields = mapOf("transferId" to transferId),
                )
                return
            }
            if (current.kind == FilePushKind.DEPOSIT) {
                engine.terminateSilent(transferId, FilePushStatus.ENDED)
            } else {
                engine.failTransfer(transferId, FilePushStatus.ENDED, FileFailureReason.ENDED)
            }
            ended = true
            return
        }
        val relayBackup =
            if (tagKind == OutboxTag.Kind.FILE_PUSH && current.kind != FilePushKind.DEPOSIT) {
                RelayBackup.PENDING
            } else {
                RelayBackup.NONE
            }
        outbox.enqueue(envelope, tag(), relayBackup, RetryClass.STANDARD)
        pushes.setMarker(transferId, envelope.packetId)
        markerSent = true
        refresh()
    }

    private suspend fun sendCancel() {
        val envelope = buildFileControlEnvelope(
            protection = protection,
            transferId = transferId,
            source = ctx.localDeviceId,
            target = current.viaRelay ?: current.targetDevice,
            createdAt = ctx.clock.now(),
            expiresAt = current.expiresAt,
            payload = FilePayload.Cancel(FileCancelReason.ABORT),
        )
        outbox.enqueueDirect(envelope)
    }

    private suspend fun persistProgress() {
        pushes.setProgress(transferId, nextToQueue, resumeFrom - 1)
    }

    private fun touchAck(at: Instant = ctx.clock.now()) {
        lastAckAt = at
    }

    fun hasQueuedSync(): Boolean = queued.isNotEmpty()

    private suspend fun accountOf(device: PeerId = current.targetDevice): AccountId? =
        ctx.devices.getAccountIdForDevice(device)
}
