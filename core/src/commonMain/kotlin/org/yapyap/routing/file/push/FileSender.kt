package org.yapyap.routing.file.push

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.TransferId
import org.yapyap.routing.file.FilePushOps
import org.yapyap.routing.file.fileChunkCount
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileSendState
import org.yapyap.routing.file.port.FileSendStatus
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FilePush
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.FilePushStore
import kotlin.uuid.Uuid

/**
 * Account-level send side (design §12): fans an orchestrator-level send out into one transfer
 * per device, serve-with-supersede, and per-account send state. Owns no transfer state
 * itself; every transfer runs as an actor in [FilePushEngine]. Terminal and progress
 * reporting is aggregated per account by the actors themselves; this side only fans out.
 */
internal class FileSender(
    private val ctx: RoutingContext,
    private val protection: FileEnvelopeProtection,
    private val plaintext: FilePlaintextSource,
    private val pushes: FilePushStore,
    private val engine: FilePushEngine,
    private val events: MutableSharedFlow<FileEvent>,
    private val chunkSizeBytes: StateFlow<Int>,
) : FilePushOps {
    /** Serialises check-and-create across concurrent pushes/serves of the same pair. */
    private val sendMutex = Mutex()

    /** One live transfer per (`fileId`, target device): a second push is a no-op. */
    override suspend fun pushFile(fileId: FileId, target: AccountId): Unit = sendMutex.withLock {
        val info = plaintext.describe(fileId) ?: run {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "File push aborted: file not held",
                fields = mapOf("fileId" to fileId, "target" to target),
            )
            events.emit(FileEvent.Failed(fileId, target, FileFailureReason.FAILED))
            return
        }
        if (info.totalBytes > SMALL_FILE_MAX_BYTES) {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "File push refused: above the small-file limit",
                fields = mapOf("fileId" to fileId, "totalBytes" to info.totalBytes),
            )
            return
        }
        val devices = peerDevices(target)
        for (device in devices) {
            val live = liveRows(fileId, device)
            if (live.any { it.status == FilePushStatus.IN_FLIGHT }) continue
            val row = live.firstOrNull { it.status == FilePushStatus.CREATED } ?: run {
                // A new send of the same file (same content id) to this device: earlier
                // finished transfers must not decide its completion or failure.
                pushes.deleteTerminalByFileAndTarget(info.fileId, device)
                createTransfer(info.fileId, device, info.totalBytes)
            }
            engine.startActor(row)
        }
        events.emit(FileEvent.OutgoingProgress(fileId, target, 0L, info.totalBytes))
    }

    override suspend fun cancelFile(fileId: FileId, target: AccountId): Unit = sendMutex.withLock {
        val devices = peerDevices(target)
        for (device in devices) {
            // Each live transfer reports its own CANCELLED end; deposits end silently.
            engine.userCancel(fileId, device)
        }
    }

    /**
     * Serve-on-REQUEST (design §9): a fresh SERVE transfer to one [device], covering the suffix
     * from the floor-aligned [fromOffset]. A live push of the same file to that device is
     * superseded — the old transfer is CANCELled, never left to race the serve.
     */
    override suspend fun serveFile(fileId: FileId, device: PeerId, fromOffset: Long): Boolean =
        sendMutex.withLock {
            val info = plaintext.describe(fileId) ?: return@withLock false
            if (info.totalBytes > SMALL_FILE_MAX_BYTES) return@withLock false
            val chunkSize = chunkSizeBytes.value
            val count = fileChunkCount(info.totalBytes, chunkSize)
            val start = (fromOffset / chunkSize).toInt().coerceIn(0, maxOf(count - 1, 0))
            engine.supersede(fileId, device)
            val now = ctx.clock.now()
            val row = FilePush(
                transferId = TransferId(Uuid.random()),
                fileId = info.fileId,
                targetDevice = device,
                kind = FilePushKind.SERVE,
                totalBytes = info.totalBytes,
                key = protection.generateTransferKey(),
                chunkSizeBytes = chunkSize,
                startIndex = start,
                nextChunkIndex = start,
                contiguousAcked = start - 1,
                markerPacketId = null,
                status = FilePushStatus.CREATED,
                createdAt = now,
                expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
            )
            pushes.insert(row)
            accountOf(device)?.let { events.emit(FileEvent.ServeStarted(fileId, it)) }
            engine.startActor(row)
            true
        }

    /**
     * Per-account send state (design §12–§13): no new table, computed from `file_pushes`
     * non-DEPOSIT rows with the same aggregation rules as the events.
     */
    override suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState> {
        val byAccount = mutableMapOf<AccountId, MutableList<FilePush>>()
        for (row in pushes.allByFile(fileId)) {
            if (row.kind == FilePushKind.DEPOSIT) continue
            val account = accountOf(row.targetDevice) ?: continue
            byAccount.getOrPut(account) { mutableListOf() }.add(row)
        }
        return byAccount.mapValues { (_, rows) -> aggregate(rows) }
    }

    internal fun aggregate(rows: List<FilePush>): FileSendState {
        if (rows.isEmpty()) {
            return FileSendState(FileSendStatus.SENDING, 0L, 0L)
        }
        val total = rows.first().totalBytes
        val completed = rows.any { it.status == FilePushStatus.COMPLETED }
        if (completed) {
            return FileSendState(FileSendStatus.COMPLETED, total, total)
        }
        val terminal = rows.filter { it.status.isTerminal }
        if (terminal.size == rows.size && rows.isNotEmpty()) {
            // Failed only when every device transfer is terminal and none completed.
            // ENDED wins over EXPIRED when mixed (an unknown outcome outranks a timeout).
            val status = when {
                terminal.any { it.status == FilePushStatus.ENDED } -> FileSendStatus.ENDED
                terminal.any { it.status == FilePushStatus.EXPIRED } -> FileSendStatus.EXPIRED
                terminal.any { it.status == FilePushStatus.DECLINED } -> FileSendStatus.DECLINED
                terminal.any { it.status == FilePushStatus.CANCELLED } -> FileSendStatus.CANCELLED
                else -> FileSendStatus.ENDED
            }
            val sent = rows.minOfOrNull { coveredBytes(it) } ?: 0L
            return FileSendState(status, sent, total)
        }
        // OutgoingProgress = minimum covered bytes across live device transfers.
        val sent = rows.filter { it.status.isLive }.minOfOrNull { coveredBytes(it) } ?: 0L
        return FileSendState(FileSendStatus.SENDING, sent, total)
    }

    private fun coveredBytes(row: FilePush): Long =
        minOf((row.contiguousAcked.toLong() + 1) * row.chunkSizeBytes, row.totalBytes).coerceAtLeast(0L)

    private suspend fun createTransfer(fileId: FileId, device: PeerId, totalBytes: Long): FilePush {
        val now = ctx.clock.now()
        val row = FilePush(
            transferId = TransferId(Uuid.random()),
            fileId = fileId,
            targetDevice = device,
            kind = FilePushKind.DIRECT,
            totalBytes = totalBytes,
            key = protection.generateTransferKey(),
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

    private suspend fun peerDevices(target: AccountId): List<PeerId> =
        ctx.devices.getAllPeerDevicesForAccount(target).filter { it != ctx.localDeviceId }

    private suspend fun liveRows(fileId: FileId, device: PeerId): List<FilePush> =
        pushes.findLiveByFileAndTarget(fileId, device)

    private suspend fun accountOf(device: PeerId): AccountId? =
        ctx.devices.getAccountIdForDevice(device)
}
