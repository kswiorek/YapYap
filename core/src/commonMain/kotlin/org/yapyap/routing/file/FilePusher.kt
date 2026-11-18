package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePush
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.FilePushStore
import org.yapyap.routing.store.OutboxTag
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Small-path direct pusher (design §6.1–§6.2, §6.5): per-device fan-out of an account-level
 * send, OFFER-as-marker lifecycle, chunk outbox rows in a `maxInFlightChunks` window,
 * stall-free flat retries, and resume from rows plus the outbox after restart.
 *
 * Stateless across restarts: all durable state lives in `file_pushes` rows plus tagged
 * outbox rows. A restart drops leftover outbox rows for live transfers and re-pumps from
 * the persisted resume point — duplicates merge at the receiver by plaintext byte coverage.
 * No deposits yet (Phase E); no pull path yet (Phase D).
 */
internal interface FileOutbox {
    suspend fun enqueue(envelope: BinaryEnvelope, tag: OutboxTag)
    suspend fun deleteByTag(tag: OutboxTag)
}

internal class FilePusher(
    private val ctx: RoutingContext,
    private val plaintext: FilePlaintextSource,
    private val pushes: FilePushStore,
    private val outbox: FileOutbox,
    private val events: MutableSharedFlow<FileEvent>,
    private val chunkSizeBytes: StateFlow<Int>,
) : FilePushOps {

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

    private suspend fun enqueue(envelope: BinaryEnvelope, tag: OutboxTag) = outbox.enqueue(envelope, tag)

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
                terminate(row, FilePushStatus.CANCELLED, FileFailureReason.CANCELLED, target)
            }
        }
    }

    /** Restart recovery: expire dead transfers, then re-arm live ones from their rows. */
    suspend fun start() {
        val now = ctx.clock.now()
        for (row in liveRows()) {
            if (now >= row.expiresAt) {
                terminate(row, FilePushStatus.EXPIRED, FileFailureReason.EXPIRED, accountOf(row.targetDevice))
                continue
            }
            // Clean slate: pre-restart outbox rows are gone with the process. Re-pump from
            // the ACKed prefix so nothing ACKed is re-sent and nothing unacked is lost —
            // duplicates merge at the receiver by plaintext byte coverage.
            try {
                outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE, row.transferId.value))
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

    /** Transport ACK / terminal NACK / expiry of a tagged outbox row. */
    suspend fun onOutboxOutcome(outcome: OutboxOutcome) {
        if (outcome.tag.kind != OutboxTag.Kind.FILE) return
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
                        terminate(row, FilePushStatus.DECLINED, FileFailureReason.DECLINED, accountOf(row.targetDevice))
                    } else {
                        // The transfer is gone at the target; quietly ENDED, never DECLINED (§3.6).
                        terminate(row, FilePushStatus.ENDED, FileFailureReason.FAILED, accountOf(row.targetDevice))
                    }
                } else {
                    terminate(row, FilePushStatus.ENDED, FileFailureReason.FAILED, accountOf(row.targetDevice))
                }
            }

            is OutboxOutcome.Expired -> {
                if (ctx.clock.now() >= row.expiresAt) {
                    terminate(row, FilePushStatus.EXPIRED, FileFailureReason.EXPIRED, accountOf(row.targetDevice))
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
            val sent = minOf((contiguousChunks.toLong() + 1) * row.chunkSizeBytes, row.totalBytes)
            emitProgress(row, sent)
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
            outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE, row.transferId.value))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        setStatus(row, FilePushStatus.COMPLETED)
        emitProgress(row, row.totalBytes)
    }

    /** The target aborted the transfer: end it quietly. */
    suspend fun onTransferCancelled(transferId: TransferId) {
        val row = try {
            pushes.find(transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        } ?: return
        if (!row.status.isLive) return
        terminate(row, FilePushStatus.CANCELLED, FileFailureReason.CANCELLED, accountOf(row.targetDevice))
    }

    // -- Internals ---------------------------------------------------------------

    private suspend fun createTransfer(info: FileDescriptorInfo, device: PeerId): FilePush {
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
            terminate(row, FilePushStatus.ENDED, FileFailureReason.FAILED, accountOf(row.targetDevice))
            return
        } ?: run {
            terminate(row, FilePushStatus.ENDED, FileFailureReason.FAILED, accountOf(row.targetDevice))
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
            terminate(row, FilePushStatus.ENDED, FileFailureReason.FAILED, accountOf(row.targetDevice))
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
                terminate(row, FilePushStatus.ENDED, FileFailureReason.FAILED, accountOf(row.targetDevice))
                return false
            }
        ) {
            is org.yapyap.routing.file.FileReadResult.Ok -> read.bytes
            else -> {
                terminate(row, FilePushStatus.ENDED, FileFailureReason.FAILED, accountOf(row.targetDevice))
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
            sendPacket(row, FilePayload.EncryptedChunk(chunkIndex, ciphertext))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            terminate(row, FilePushStatus.ENDED, FileFailureReason.FAILED, accountOf(row.targetDevice))
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

    private suspend fun sendPacket(row: FilePush, payload: FilePayload): Uuid? {
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
                target = row.targetDevice,
                payload = envelope.encode(),
            )
            enqueue(binary, OutboxTag(OutboxTag.Kind.FILE, row.transferId.value))
            binary.packetId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            terminate(row, FilePushStatus.ENDED, FileFailureReason.FAILED, accountOf(row.targetDevice))
            null
        }
    }

    private suspend fun terminate(row: FilePush, status: FilePushStatus, reason: FileFailureReason, target: AccountId?) {
        require(status.isTerminal) { "terminate() needs a terminal status" }
        try {
            outbox.deleteByTag(OutboxTag(OutboxTag.Kind.FILE, row.transferId.value))
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
        events.emit(FileEvent.Failed(row.fileId, target, reason))
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

    private fun fileCount(row: FilePush): Int =
        if (row.totalBytes == 0L) 0
        else ((row.totalBytes - 1) / row.chunkSizeBytes + 1).toInt()
}
