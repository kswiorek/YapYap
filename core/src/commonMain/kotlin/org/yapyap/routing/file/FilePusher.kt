package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.policy.RelaySelectionPolicy
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Small-path direct pusher (design §6.1–§6.2, §6.5): per-device fan-out of an account-level
 * send, OFFER-as-marker lifecycle, chunk outbox rows in a `maxInFlightChunks` window,
 * stall-free flat retries, and resume from rows plus the outbox after restart.
 *
 * Stateless across restarts except the stall clock: all durable state lives in
 * `file_pushes` rows plus tagged outbox rows. A restart drops leftover outbox rows
 * for live transfers and re-pumps from the persisted resume point — duplicates
 * merge at the receiver by plaintext byte coverage. Deposit rounds are idempotent
 * across restarts (DEPOSIT rows in the store are the claim), so the worst case of
 * a lost in-memory ACK clock is one late deposit, never two rounds.
 *
 * DEPOSIT rows are relay-path state: silent (no [FileEvent] ever names a relay or
 * a deposit), sealed to the ultimate target, outer-addressed to their relay.
 */
internal interface FileOutbox {
    suspend fun enqueue(
        envelope: BinaryEnvelope,
        tag: OutboxTag,
        relayBackup: RelayBackup = RelayBackup.NONE,
        retryClass: RetryClass = RetryClass.STANDARD,
    )
    suspend fun enqueueDirect(envelope: BinaryEnvelope)
    suspend fun deleteByTag(tag: OutboxTag)

    /** Whether any outbox row still carries [tag] (stall detection, forward resume). */
    suspend fun hasQueued(tag: OutboxTag): Boolean

    /** Whether the outbox still holds the row for [packetId] (forward resume). */
    suspend fun isPacketQueued(packetId: Uuid): Boolean
}

internal class FilePusher(
    private val ctx: RoutingContext,
    private val plaintext: FilePlaintextSource,
    private val pushes: FilePushStore,
    private val outbox: FileOutbox,
    private val events: MutableSharedFlow<FileEvent>,
    private val chunkSizeBytes: StateFlow<Int>,
    /**
     * Relays a remainder is deposited with (§6.4). No-op by default (unit tests
     * drive deposits explicitly); production passes the reliability policy.
     */
    private val relaySelection: RelaySelectionPolicy = NoRelaySelection,
) : FilePushOps {

    /**
     * Last transport/coverage ACK per live authoring transfer (the stall clock,
     * §6.3). In-memory by design: losing it across a restart only delays one
     * deposit by one timeout, and DEPOSIT rows in the store keep the round
     * idempotent regardless.
     */
    private val lastAckAt = HashMap<TransferId, Instant>()
    private val ackLock = Mutex()

    /** One live transfer per (`fileId`, target device): a second push supersedes nothing yet. */
    override suspend fun pushFile(fileId: FileId, target: AccountId) {
        val info = try {
            plaintext.describe(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                message = "File push aborted: descriptor unreadable",
                throwable = e,
                fields = mapOf("fileId" to fileId, "target" to target),
            )
            events.emit(FileEvent.Failed(fileId, target, FileFailureReason.FAILED))
            return
        } ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                message = "File push aborted: file not held",
                fields = mapOf("fileId" to fileId, "target" to target),
            )
            events.emit(FileEvent.Failed(fileId, target, FileFailureReason.FAILED))
            return
        }
        val devices = try {
            ctx.devices.getAllPeerDevicesForAccount(target).filter { it != ctx.localDeviceId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                message = "File push aborted: no peer devices resolvable",
                throwable = e,
                fields = mapOf("fileId" to fileId, "target" to target),
            )
            return
        }
        for (device in devices) {
            val live = try {
                pushes.findLiveByFileAndTarget(fileId, device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                    message = "File push aborted: push store unreadable",
                    throwable = e,
                    fields = mapOf("fileId" to fileId, "target" to device),
                )
                continue
            }
            if (live.any { it.status == FilePushStatus.IN_FLIGHT }) continue
            val row = live.firstOrNull { it.status == FilePushStatus.CREATED } ?: createTransfer(info, device)
            sendMarker(row)
        }
        events.emit(FileEvent.OutgoingProgress(fileId, target, 0L, info.totalBytes))
    }

    private suspend fun enqueue(
        envelope: BinaryEnvelope,
        tag: OutboxTag,
        relayBackup: RelayBackup = RelayBackup.NONE,
        retryClass: RetryClass = RetryClass.STANDARD,
    ) = outbox.enqueue(envelope, tag, relayBackup, retryClass)

    override suspend fun cancelFile(fileId: FileId, target: AccountId) {
        val devices = try {
            ctx.devices.getAllPeerDevicesForAccount(target).filter { it != ctx.localDeviceId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        for (device in devices) {
            for (row in liveRows(fileId, device)) {
                // User cancel CANCELs every active transfer and deposit (§6.5): the
                // envelope tells the other end to drop its rows at once instead of
                // waiting out the TTL. Deposits go to their relay, direct to target.
                sendCancel(row)
                terminate(row, FilePushStatus.CANCELLED, FileFailureReason.CANCELLED, target)
            }
        }
    }

    /**
     * Serve-on-REQUEST (design §9): a fresh SERVE transfer to one [device], covering the
     * suffix from the floor-aligned [fromOffset]. A live push of the same file to that
     * device is superseded — the old transfer is CANCELled, never left to race the
     * serve. False when not held (or large, which is never served by transfer).
     */
    override suspend fun serveFile(fileId: FileId, device: PeerId, fromOffset: Long): Boolean {
        val info = try {
            plaintext.describe(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        } ?: return false
        if (info.totalBytes > SMALL_FILE_MAX_BYTES) return false
        val chunkSize = chunkSizeBytes.value
        val count = fileCount(info.totalBytes, chunkSize)
        val start = (fromOffset / chunkSize).toInt().coerceIn(0, maxOf(count - 1, 0))
        for (row in liveRows(fileId, device)) {
            supersede(row)
        }
        val now = ctx.clock.now()
        val row = FilePush(
            transferId = TransferId(Uuid.random()),
            fileId = info.fileId,
            targetDevice = device,
            kind = FilePushKind.SERVE,
            totalBytes = info.totalBytes,
            key = ctx.envelopeProtectionService.generateFileTransferKey(),
            chunkSizeBytes = chunkSize,
            startIndex = start,
            nextChunkIndex = start,
            contiguousAcked = start - 1,
            markerPacketId = null,
            status = FilePushStatus.CREATED,
            createdAt = now,
            expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
        )
        try {
            pushes.insert(row)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        accountOf(device)?.let { events.emit(FileEvent.ServeStarted(fileId, it)) }
        sendMarker(row)
        return true
    }

    /**
     * Supersede a live transfer with a fresh serve: CANCEL the old transferId (so the
     * other end drops its row instead of answering late chunks DECLINED), drop its
     * rows, and stay silent — this is a handoff, not a failure, so no [FileEvent.Failed].
     * The CANCEL goes to the outer target (the relay for a deposit, §6.5).
     */
    private suspend fun supersede(row: FilePush) {
        try {
            pushes.setStatus(row.transferId, FilePushStatus.CANCELLED)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        try {
            outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_PUSH, row.transferId.value))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        sendCancel(row)
    }

    /** Best-effort CANCEL at the row's outer target (direct target, or relay holding the deposit). */
    private suspend fun sendCancel(row: FilePush) {
        try {
            val envelope = buildFileControlEnvelope(
                protection = ctx.envelopeProtectionService,
                transferId = row.transferId,
                source = ctx.localDeviceId,
                target = row.viaRelay ?: row.targetDevice,
                createdAt = ctx.clock.now(),
                expiresAt = row.expiresAt,
                payload = FilePayload.Cancel(FileCancelReason.ABORT, reasonText = null),
            )
            outbox.enqueueDirect(envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Restart recovery: expire dead transfers, then re-arm live ones from their rows. */
    suspend fun start() {
        val now = ctx.clock.now()
        for (row in liveRows()) {
            touchAck(row.transferId, now)
            if (now >= row.expiresAt) {
                terminate(row, FilePushStatus.EXPIRED, FileFailureReason.EXPIRED, accountOf(row.targetDevice))
                continue
            }
            // Clean slate: pre-restart outbox rows are gone with the process. Re-pump from
            // the ACKed prefix so nothing ACKed is re-sent and nothing unacked is lost —
            // duplicates merge at the receiver by plaintext byte coverage.
            try {
                outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_PUSH, row.transferId.value))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            val resumeFrom = maxOf(row.startIndex, row.contiguousAcked + 1)
            if (resumeFrom != row.nextChunkIndex) {
                try {
                    pushes.setProgress(row.transferId, resumeFrom, row.contiguousAcked)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
            }
            if (row.status == FilePushStatus.CREATED) {
                sendMarker(row)
            } else {
                pump(row.copy(nextChunkIndex = resumeFrom))
            }
        }
    }

    /**
     * Runs the pusher: recovery once, then the deposit-on-stall sweep (§6.3) plus the
     * at-once deposit on link close. Uniform over every live authoring transfer —
     * direct pushes and REQUEST serves alike, whoever triggered them — never over
     * DEPOSIT rows (the sender never chases a failing relay, §6.4).
     */
    fun runIn(scope: CoroutineScope): Job = scope.launch {
        start()
        val linkDowns = launch {
            ctx.peerLinks.linkDowns.collect { peer ->
                runCatching { depositForPeer(peer) }
            }
        }
        try {
            while (true) {
                // Poll slower than the timeout so one silent interval always trips it.
                delay(maxOf(ctx.routerConfig.value.chunkAckTimeout / 3, 5.seconds))
                runCatching { sweepStalls() }
            }
        } finally {
            linkDowns.cancel()
        }
    }

    /** Chunk-phase deposit sweep (§6.3): gone-only signals, never one slow chunk. */
    internal suspend fun sweepStalls() {
        val now = ctx.clock.now()
        val timeout = ctx.routerConfig.value.chunkAckTimeout
        for (row in liveRows()) {
            if (row.kind != FilePushKind.DIRECT && row.kind != FilePushKind.SERVE) continue
            if (row.status != FilePushStatus.IN_FLIGHT) continue // marker phase has its own triggers
            if (now >= row.expiresAt) continue
            if (!hasQueued(row)) continue // nothing in flight: nothing to insure
            val lastAck = ackLock.withLock { lastAckAt[row.transferId] } ?: row.createdAt
            val noLink = try {
                ctx.peerLinks.linksOf(row.targetDevice).isEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (noLink || now - lastAck > timeout) {
                // One slow or retried chunk is not a signal: only the absence of
                // every ACK — no link now, or no ACK at all within the timeout.
                depositRemainder(row.transferId)
            }
        }
    }

    /** Link-close deposit, at once (§6.3): whoever that peer was getting from us. */
    private suspend fun depositForPeer(peer: PeerId) {
        for (row in liveRows()) {
            if (row.targetDevice != peer) continue
            if (row.kind != FilePushKind.DIRECT && row.kind != FilePushKind.SERVE) continue
            if (row.status != FilePushStatus.IN_FLIGHT) continue
            if (hasQueued(row)) depositRemainder(row.transferId)
        }
    }

    /**
     * Deposit the remainder of [transferId] at the policy-selected relays (§6.4):
     * one round, one transfer with its own key and sealed OFFER per relay, covering
     * the suffix after the highest contiguous ACKed prefix. At most once per
     * (file, target) — DEPOSIT rows in the store are the claim, so this is also
     * restart-safe. Returns how many relay deposits were started.
     */
    suspend fun depositRemainder(transferId: TransferId): Int {
        val row = try {
            pushes.find(transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return 0
        } ?: return 0
        if (!row.status.isLive) return 0
        if (row.kind != FilePushKind.DIRECT && row.kind != FilePushKind.SERVE) return 0
        val live = liveRows(row.fileId, row.targetDevice)
        if (live.any { it.kind == FilePushKind.DEPOSIT && it.status.isLive }) return 0
        val fileCount = fileCount(row)
        val resumeFrom = maxOf(row.startIndex, row.contiguousAcked + 1)
        if (resumeFrom >= fileCount) return 0 // fully ACKed: nothing to insure
        val relays = try {
            relaySelection.selectRelays(row.targetDevice)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (relays.isEmpty()) return 0 // no relay qualifies: the pull path covers it
        var started = 0
        for (relay in relays) {
            val now = ctx.clock.now()
            val deposit = FilePush(
                transferId = TransferId(Uuid.random()),
                fileId = row.fileId,
                targetDevice = row.targetDevice,
                viaRelay = relay,
                kind = FilePushKind.DEPOSIT,
                totalBytes = row.totalBytes,
                key = ctx.envelopeProtectionService.generateFileTransferKey(),
                chunkSizeBytes = row.chunkSizeBytes,
                startIndex = resumeFrom,
                nextChunkIndex = resumeFrom,
                contiguousAcked = resumeFrom - 1,
                markerPacketId = null,
                status = FilePushStatus.CREATED,
                createdAt = now,
                expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
            )
            try {
                pushes.insert(deposit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            touchAck(deposit.transferId, now)
            sendMarker(deposit)
            started += 1
        }
        return started
    }

    /**
     * Outbox backup path for an OFFER marker (step 19): the marker's packet id maps
     * back to its transfer, whose remainder is deposited. DEPOSIT markers map to
     * DEPOSIT rows and no-op here (never chase a failing relay, §6.4).
     */
    suspend fun depositByMarkerPacket(markerPacketId: Uuid): Int {
        val row = try {
            pushes.findByMarkerPacket(markerPacketId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return 0
        } ?: return 0
        return depositRemainder(row.transferId)
    }

    /** Transport ACK / terminal NACK / expiry of a tagged outbox row. */
    suspend fun onOutboxOutcome(outcome: OutboxOutcome) {
        if (outcome.tag.kind != OutboxTag.Kind.FILE_PUSH) return
        val row = try {
            pushes.find(TransferId(outcome.tag.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        } ?: return
        if (!row.status.isLive) return
        when (outcome) {
            is OutboxOutcome.Acked -> {
                touchAck(row.transferId)
                if (outcome.packetId == row.markerPacketId) {
                    if (row.status == FilePushStatus.CREATED) {
                        setStatus(row, FilePushStatus.IN_FLIGHT)
                    }
                    pump(row.copy(status = FilePushStatus.IN_FLIGHT))
                } else {
                    pump(row)
                }
            }

            is OutboxOutcome.Nacked -> {
                if (outcome.reason == PacketNackReason.DECLINED) {
                    if (outcome.packetId == row.markerPacketId) {
                        if (row.kind == FilePushKind.DEPOSIT) {
                            // A relay declining the deposit OFFER (full) ends that
                            // depot quietly — the send itself is unaffected (§3.6).
                            terminateSilent(row, FilePushStatus.ENDED)
                        } else {
                            terminate(
                                row,
                                FilePushStatus.DECLINED,
                                FileFailureReason.DECLINED,
                                accountOf(row.targetDevice)
                            )
                        }
                    } else {
                        // The transfer is gone at the target; quietly ENDED, never DECLINED (§3.6).
                        if (row.kind == FilePushKind.DEPOSIT) terminateSilent(row, FilePushStatus.ENDED)
                        else terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
                    }
                } else {
                    if (row.kind == FilePushKind.DEPOSIT) terminateSilent(row, FilePushStatus.ENDED)
                    else terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
                }
            }

            is OutboxOutcome.Expired -> {
                if (ctx.clock.now() >= row.expiresAt) {
                    if (row.kind == FilePushKind.DEPOSIT) terminateSilent(row, FilePushStatus.EXPIRED)
                    else terminate(row, FilePushStatus.EXPIRED, FileFailureReason.EXPIRED, accountOf(row.targetDevice))
                } else {
                    rearm(row, outcome.packetId)
                }
            }
        }
    }

    /** Coverage ACK from the target (progress in the transfer's own chunk indices). */
    suspend fun onCoverageAck(transferId: TransferId, contiguousChunks: Int, missingRanges: List<org.yapyap.protocol.envelopes.ChunkRange>) {
        val row = try {
            pushes.find(transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        } ?: return
        if (!row.status.isLive) return
        if (contiguousChunks > row.contiguousAcked) {
            try {
                pushes.setProgress(row.transferId, row.nextChunkIndex, contiguousChunks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            touchAck(row.transferId)
            // Deposit progress stays routing-internal (no orchestrator leak).
            if (row.kind != FilePushKind.DEPOSIT) {
                val sent = minOf((contiguousChunks.toLong() + 1) * row.chunkSizeBytes, row.totalBytes)
                emitProgress(row, sent)
            }
            pump(row.copy(contiguousAcked = contiguousChunks))
        }
        // Missing ranges are left to fill by retry + resume; no chasing in v1.
    }

    /** The target has everything: resolve the transfer as COMPLETED and cancel deposits. */
    suspend fun onTransferComplete(transferId: TransferId) {
        val row = try {
            pushes.find(transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        } ?: return
        if (!row.status.isLive) return
        try {
            outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_PUSH, row.transferId.value))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        setStatus(row, FilePushStatus.COMPLETED)
        if (row.kind != FilePushKind.DEPOSIT) {
            emitProgress(row, row.totalBytes)
            accountOf(row.targetDevice)?.let { events.emit(FileEvent.OutgoingComplete(row.fileId, it)) }
        }
        // Target-confirmed receipt ends every deposit of the same file to the same
        // device too: CANCEL each at its outer target so relays free quota at once
        // (§6.5). Silent — the send completed, this is just hygiene.
        for (other in liveRows(row.fileId, row.targetDevice)) {
            if (other.transferId == row.transferId || !other.status.isLive) continue
            sendCancel(other)
            terminateSilent(other, FilePushStatus.CANCELLED)
        }
    }

    /** The target aborted the transfer: end it quietly. */
    /**
     * The target cancelled [transferId] with [reason]. A refusal, for lack of space or otherwise,
     * reads as DECLINED (design §8); an abort ends the transfer with its outcome unknown.
     */
    suspend fun onTransferCancelled(transferId: TransferId, reason: FileCancelReason) {
        val row = try {
            pushes.find(transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        } ?: return
        if (!row.status.isLive) return
        when (reason) {
            FileCancelReason.DECLINED,
            FileCancelReason.NO_SPACE,
                -> terminate(row, FilePushStatus.DECLINED, FileFailureReason.DECLINED, accountOf(row.targetDevice))

            FileCancelReason.ABORT ->
                terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
        }
    }

    // -- Internals ---------------------------------------------------------------

    private suspend fun createTransfer(info: FileManifest, device: PeerId): FilePush {
        val now = ctx.clock.now()
        val row = FilePush(
            transferId = TransferId(Uuid.random()),
            fileId = info.fileId,
            targetDevice = device,
            kind = FilePushKind.DIRECT,
            totalBytes = info.totalBytes,
            key = ctx.envelopeProtectionService.generateFileTransferKey(),
            chunkSizeBytes = chunkSizeBytes.value,
            startIndex = 0,
            nextChunkIndex = 0,
            contiguousAcked = -1,
            markerPacketId = null,
            status = FilePushStatus.CREATED,
            createdAt = now,
            expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
        )
        pushes.insert(row)
        return row
    }

    private suspend fun sendMarker(row: FilePush) {
        val info = try {
            plaintext.describe(row.fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
            return
        } ?: run {
            terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
            return
        }
        val sealed = try {
            ctx.envelopeProtectionService.sealFileOffer(
                FilePayload.SealedOffer(
                    fileId = info.fileId,
                    blockSize = info.blockSize,
                    blockHashes = info.blockHashes,
                    transferKey = row.key,
                    fileName = info.fileName,
                    mimeType = info.mimeType,
                ),
                row.targetDevice,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            if (e.disposition == org.yapyap.protection.ProtectionDisposition.DEFER) {
                // Session not ready yet: keep the CREATED row; the next push/start re-arms.
                AppLog.warn(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                    message = "File OFFER deferred: session not ready; re-armed later",
                    fields = mapOf("transferId" to row.transferId),
                )
                return
            }
            terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
            return
        }
        val packetId = sendPacket(
            row = row,
            payload = FilePayload.Offer(
                chunkSizeBytes = row.chunkSizeBytes,
                totalBytes = info.totalBytes,
                startIndex = row.startIndex,
                sealedOfferBytes = sealed,
            ),
            // Authoring markers use the message triggers (§5, step 19); deposit
            // markers are already at a relay and chunk rows have their own signals.
            relayBackup = if (row.kind == FilePushKind.DEPOSIT) RelayBackup.NONE else RelayBackup.PENDING,
        ) ?: return
        try {
            pushes.setMarker(row.transferId, packetId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun pump(row: FilePush) {
        val current = try {
            pushes.find(row.transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        } ?: return
        if (!current.status.isLive) return
        val window = ctx.routerConfig.value.maxInFlightChunks
        val fileCount = fileCount(current)
        var next = current.nextChunkIndex
        while (next - current.contiguousAcked - 1 < window && next < fileCount) {
            if (!sendChunk(current, next)) break
            next += 1
            try {
                pushes.setProgress(current.transferId, next, current.contiguousAcked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
        }
    }

    private suspend fun sendChunk(row: FilePush, chunkIndex: Int): Boolean {
        val offset = chunkIndex.toLong() * row.chunkSizeBytes
        val length = minOf(row.chunkSizeBytes.toLong(), row.totalBytes - offset).toInt()
        if (length <= 0) return false
        val plaintext = when (
            val read = try {
                this.plaintext.read(row.fileId, offset, length)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
                return false
            }
        ) {
            is org.yapyap.routing.file.port.FileReadResult.Ok -> read.bytes
            else -> {
                terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
                return false
            }
        }
        val packetId = try {
            val ciphertext = ctx.envelopeProtectionService.encryptFileChunk(
                row.transferId,
                chunkIndex,
                row.key,
                plaintext,
            )
            sendPacket(row, FilePayload.EncryptedChunk(chunkIndex, ciphertext), retryClass = RetryClass.FLAT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
            return false
        } ?: return false
        return packetId != null
    }

    private suspend fun rearm(row: FilePush, packetId: Uuid) {
        if (packetId == row.markerPacketId) {
            sendMarker(row)
            return
        }
        // Chunk rows carry no index mapping across restarts; re-pump from the resume
        // point instead of chasing one row (duplicates merge at the receiver).
        pump(row)
    }

    private suspend fun sendPacket(
        row: FilePush,
        payload: FilePayload,
        relayBackup: RelayBackup = RelayBackup.NONE,
        retryClass: RetryClass = RetryClass.STANDARD,
    ): Uuid? {
        return try {
            val envelope = ctx.envelopeProtectionService.protectFile(
                payload,
                org.yapyap.protection.service.EnvelopeProtectContext(
                    createdAt = ctx.clock.now(),
                    sourceDeviceId = ctx.localDeviceId,
                    targetDeviceId = row.targetDevice,
                    securityScheme = org.yapyap.protocol.SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
                    transferId = row.transferId,
                ),
            )
            val binary = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = org.yapyap.protocol.PacketType.FILE,
                dispositionRequested = true,
                createdAt = ctx.clock.now(),
                expiresAt = row.expiresAt,
                source = ctx.localDeviceId,
                // Deposits ride outer-addressed to their relay; the inner envelope
                // stays targeted at the ultimate device (one jump, §6.4).
                target = row.viaRelay ?: row.targetDevice,
                payload = envelope.encode(),
            )
            enqueue(binary, OutboxTag(OutboxTag.Kind.FILE_PUSH, row.transferId.value), relayBackup, retryClass)
            binary.packetId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            terminate(row, FilePushStatus.ENDED, FileFailureReason.ENDED, accountOf(row.targetDevice))
            null
        }
    }

    /**
     * Silent terminate: drops outbox rows and marks the row without telling the
     * orchestrator. Relay-path hygiene only — deposits and cancelled deposits never
     * surface as account-level events.
     */
    private suspend fun terminateSilent(row: FilePush, status: FilePushStatus) {
        require(status.isTerminal) { "terminateSilent() needs a terminal status" }
        try {
            outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_PUSH, row.transferId.value))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        try {
            pushes.setStatus(row.transferId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun terminate(row: FilePush, status: FilePushStatus, reason: FileFailureReason, target: AccountId?) {
        require(status.isTerminal) { "terminate() needs a terminal status" }
        try {
            outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE_PUSH, row.transferId.value))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        try {
            pushes.setStatus(row.transferId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        // Deposits stay routing-internal (no orchestrator leak): the send they insure
        // reports on its own direct transfer, never on a depot. A device no longer in the
        // directory has no account to report to.
        if (row.kind != FilePushKind.DEPOSIT && target != null) {
            events.emit(FileEvent.Failed(row.fileId, target, reason))
        }
    }

    private suspend fun setStatus(row: FilePush, status: FilePushStatus) {
        try {
            pushes.setStatus(row.transferId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun emitProgress(row: FilePush, sentBytes: Long) {
        val target = accountOf(row.targetDevice) ?: return
        events.emit(FileEvent.OutgoingProgress(row.fileId, target, sentBytes, row.totalBytes))
    }

    private suspend fun touchAck(transferId: TransferId, at: Instant = ctx.clock.now()) {
        ackLock.withLock { lastAckAt[transferId] = at }
    }

    private suspend fun hasQueued(row: FilePush): Boolean = try {
        outbox.hasQueued(OutboxTag(OutboxTag.Kind.FILE_PUSH, row.transferId.value))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun liveRows(fileId: FileId? = null, device: PeerId? = null): List<FilePush> {
        return try {
            if (fileId != null && device != null) pushes.findLiveByFileAndTarget(fileId, device)
            else pushes.live()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun accountOf(device: PeerId): AccountId? = try {
        ctx.devices.getAccountIdForDevice(device)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun fileCount(row: FilePush): Int = fileCount(row.totalBytes, row.chunkSizeBytes)

    private fun fileCount(totalBytes: Long, chunkSizeBytes: Int): Int =
        if (totalBytes == 0L) 0
        else ((totalBytes - 1) / chunkSizeBytes + 1).toInt()
}
