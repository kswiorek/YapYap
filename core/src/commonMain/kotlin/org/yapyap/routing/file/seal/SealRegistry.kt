package org.yapyap.routing.file.seal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileSeal
import org.yapyap.protocol.envelopes.TransferShape
import org.yapyap.routing.file.TransferEvent
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileSendState
import org.yapyap.routing.file.port.FileSendStatus
import org.yapyap.routing.file.transfer.TransferEngine
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileSealKind
import org.yapyap.routing.store.FileSealStatus
import org.yapyap.routing.store.FileSealStore
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.routing.store.OutgoingSeal
import org.yapyap.routing.store.OutgoingTransfer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The origin side of the small path (docs/envelope-redesign.md §5.2–§5.6): seals and what becomes
 * of them. A push seals the file once per target device and opens the direct transfer; when that
 * transfer's peer looks gone, one deposit round carries the same seal and the remainder to the
 * relays the policy picks. The seal's state follows from its transfers:
 *
 * - the direct transfer finishing, or a COMPLETE from the target → DELIVERED (live deposits are
 *   cancelled so relays free their quota);
 * - the target refusing the OFFER → DECLINED;
 * - a deposit becoming whole at its relay → HANDED_OFF (the direct push stops: the relays deliver);
 * - every transfer ended otherwise → ENDED, and the pull path recovers.
 *
 * A serve answers a REQUEST with a fresh seal, always (a seal the requester cannot open again must
 * never be offered twice). Account-level events report the aggregate of an account's devices.
 */
internal class SealRegistry(
    private val ctx: RoutingContext,
    private val sealStore: FileSealStore,
    private val transferStore: FileTransferStore,
    private val engine: TransferEngine,
    private val plaintext: FilePlaintextSource,
    private val relaySelection: RelaySelectionPolicy,
    private val events: MutableSharedFlow<FileEvent>,
) {
    /** Serialises seal state changes, so an account's aggregate is computed once per change. */
    private val mutex = Mutex()
    //TODO: async fanouts
    /** Seals [fileId] for every device of [target] that has no live seal of it and pushes it. */
    suspend fun push(fileId: FileId, target: AccountId) {
        val manifest = plaintext.describe(fileId) ?: run {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "File push aborted: file not held",
                fields = mapOf("fileId" to fileId, "target" to target),
            )
            events.emit(FileEvent.Failed(fileId, target, FileFailureReason.FAILED))
            return
        }
        if (manifest.totalBytes > SMALL_FILE_MAX_BYTES) return
        mutex.withLock {
            for (device in peerDevices(target)) {
                val live =
                    sealStore.byFileAndTarget(fileId, device).any { it.kind == FileSealKind.PUSH && it.status.isLive }
                if (!live) createSeal(manifest, device, FileSealKind.PUSH, byteFrom = 0)
            }
        }
        report(fileId, target)
    }

    /**
     * Serves [fileId] to [device] from the chunk boundary at or below [fromOffset], with a fresh
     * seal. A REQUEST repeated within one attempt is answered by the serve already under way, and
     * any other live transfer of the file to that device is cancelled: one transfer of a file
     * from us to one device at a time.
     */
    suspend fun serve(fileId: FileId, device: PeerId, fromOffset: Long): Boolean {
        val manifest = plaintext.describe(fileId) ?: return false
        if (manifest.totalBytes > SMALL_FILE_MAX_BYTES) return false
        val chunkSize = ctx.fileLimits.value.chunkSizeBytes
        val byteFrom = (fromOffset.coerceIn(0, manifest.totalBytes) / chunkSize) * chunkSize
        mutex.withLock {
            val now = ctx.clock.now()
            val direct = sealStore.byFileAndTarget(fileId, device).filter { it.status.isLive }
                .flatMap { seal ->
                    transferStore.bySeal(seal.sealId).filter { it.peer == device && it.status.isLive }
                        .map { seal to it }
                }
            val underWay = direct.any { (seal, transfer) ->
                seal.kind == FileSealKind.SERVE && transfer.shape.byteFrom <= byteFrom &&
                        now - transfer.createdAt < ctx.routerConfig.value.fileRequestAttemptTimeout
            }
            if (underWay) return true
            direct.forEach { (_, transfer) -> engine.cancel(transfer.transferId) }
            if (!createSeal(manifest, device, FileSealKind.SERVE, byteFrom)) return false
        }
        ctx.devices.getAccountIdForDevice(device)?.let { events.emit(FileEvent.ServeStarted(fileId, it)) }
        return true
    }

    /** User cancel: every live push seal of [fileId] to [target]'s devices ends with its transfers. */
    //TODO: async fanout
    suspend fun cancel(fileId: FileId, target: AccountId) {
        mutex.withLock {
            for (device in peerDevices(target)) {
                for (seal in sealStore.byFileAndTarget(fileId, device)) {
                    if (seal.kind != FileSealKind.PUSH || !seal.status.isLive) continue
                    sealStore.setStatus(seal.sealId, FileSealStatus.CANCELLED)
                    endLiveTransfers(seal)
                }
            }
        }
        report(fileId, target)
    }

    /** The seal's target holds the file. */
    suspend fun onComplete(sealId: ContentId, from: PeerId) {
        val seal = sealStore.find(sealId) ?: return
        if (from != seal.targetDevice) return
        mutex.withLock {
            val current = sealStore.find(sealId) ?: return
            if (current.status != FileSealStatus.DELIVERED) {
                sealStore.setStatus(sealId, FileSealStatus.DELIVERED)
                endLiveTransfers(current)
            }
        }
        report(seal)
    }

    /** Per-account state of our pushes of [fileId]. */
    suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState> =
        pushesByAccount(fileId).mapValues { (_, accountSeals) -> aggregate(accountSeals) }

    /**
     * Reacts to our transfers' events (forwards are the relay side's), and sweeps expired seals.
     * Subscribed before the engine runs, so no event of a live transfer is missed; the sweep's
     * settle covers what a restart cut short.
     */
    fun runIn(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { event ->
                if (event.transfer.isForward) return@collect
                guarded("transfer event") { onTransferEvent(event) }
            }
        }
        while (true) {
            guarded("sweep") { sweep() }
            delay(maxOf(ctx.routerConfig.value.chunkAckTimeout, 10.seconds))
        }
    }

    private suspend fun onTransferEvent(event: TransferEvent) {
        val seal = sealStore.find(event.transfer.sealId) ?: return
        when (event) {
            is TransferEvent.Progress -> if (event.transfer.peer == seal.targetDevice) report(seal)
            is TransferEvent.Gone -> if (event.transfer.peer == seal.targetDevice) deposit(seal, event.transfer)
            is TransferEvent.Ended -> {
                mutex.withLock { settle(seal.sealId) }
                report(seal)
            }
        }
    }

    /**
     * One deposit round per seal (claimed durably): one transfer to each relay the policy picks,
     * carrying the same seal and the remainder after the target's ACKed prefix. Relays are picked
     * before the claim, so an empty choice never burns the round.
     */
    private suspend fun deposit(seal: OutgoingSeal, direct: OutgoingTransfer) {
        mutex.withLock {
            val current = sealStore.find(seal.sealId) ?: return
            if (current.status != FileSealStatus.SENDING) return
            val shape = direct.shape
            val byteFrom = shape.chunkOffset(direct.contiguousAcked + 1).coerceAtMost(shape.byteTo)
            if (byteFrom >= shape.byteTo && shape.chunkCount > 0) return
            val relays = relaySelection.selectRelays(seal.targetDevice).filter { it != seal.targetDevice }
            if (relays.isEmpty()) return
            if (!sealStore.claimDeposit(seal.sealId, ctx.clock.now())) return
            for (relay in relays) {
                engine.start(newTransfer(seal, relay, TransferShape(shape.chunkSizeBytes, byteFrom, shape.byteTo)))
            }
            AppLog.info(
                component = LogComponent.FILE,
                event = LogEvent.FILE_DEPOSITED,
                message = "Deposited a seal's remainder at relays",
                fields = mapOf("sealId" to seal.sealId, "relays" to relays.size, "byteFrom" to byteFrom),
            )
        }
    }

    /** Derives the seal's state from its transfers (see the class doc) and acts on the change. */
    private suspend fun settle(sealId: ContentId) {
        val seal = sealStore.find(sealId) ?: return
        if (!seal.status.isLive) return
        val all = transferStore.bySeal(sealId)
        val direct = all.filter { it.peer == seal.targetDevice }
        val deposits = all - direct.toSet()
        val next = when {
            direct.any { it.status == FileTransferStatus.DONE } -> FileSealStatus.DELIVERED
            direct.any { it.status == FileTransferStatus.DECLINED } -> FileSealStatus.DECLINED
            deposits.any { it.status == FileTransferStatus.DONE } -> FileSealStatus.HANDED_OFF
            seal.status == FileSealStatus.HANDED_OFF -> FileSealStatus.HANDED_OFF
            all.none { it.status.isLive } -> FileSealStatus.ENDED
            else -> FileSealStatus.SENDING
        }
        if (next == seal.status) return
        sealStore.setStatus(sealId, next)
        when (next) {
            FileSealStatus.DELIVERED, FileSealStatus.DECLINED -> endLiveTransfers(seal)
            // The relays deliver the rest; pushing it directly too would only duplicate traffic.
            FileSealStatus.HANDED_OFF -> direct.filter { it.status.isLive }.forEach { engine.cancel(it.transferId) }
            else -> Unit
        }
    }

    /** Expired seals go, with their transfers; a seal still sending reports EXPIRED first. */
    private suspend fun sweep() {
        val now = ctx.clock.now()
        for (seal in sealStore.live()) mutex.withLock { settle(seal.sealId) }
        for (seal in sealStore.expiredAt(now)) {
            mutex.withLock {
                if (seal.status == FileSealStatus.SENDING) sealStore.setStatus(seal.sealId, FileSealStatus.EXPIRED)
                endLiveTransfers(seal)
            }
            report(seal)
            mutex.withLock {
                transferStore.bySeal(seal.sealId).forEach { transferStore.delete(it.transferId) }
                sealStore.delete(seal.sealId)
                if (sealStore.byFile(seal.fileId).isEmpty()) reported.keys.removeAll { it.first == seal.fileId }
            }
        }
    }

    /**
     * Seals [manifest] for [device] and opens the direct transfer from [byteFrom]. False when the
     * seal can't be made now (no session yet): the pull path covers that device.
     */
    private suspend fun createSeal(
        manifest: FileManifest,
        device: PeerId,
        kind: FileSealKind,
        byteFrom: Long,
    ): Boolean {
        val protection = ctx.envelopeProtections.file
        val key = protection.generateSealKey()
        val sealBytes = try {
            protection.seal(
                FileSeal(
                    fileId = manifest.fileId,
                    totalBytes = manifest.totalBytes,
                    blockSize = manifest.blockSize,
                    blockHashes = manifest.blockHashes,
                    fileName = manifest.fileName,
                    mimeType = manifest.mimeType,
                    key = key,
                ),
                device,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Could not seal the file for a device; the pull path covers it",
                fields = mapOf("fileId" to manifest.fileId, "device" to device, "disposition" to e.disposition),
            )
            return false
        }
        val now = ctx.clock.now()
        val seal = OutgoingSeal(
            sealId = ctx.envelopeProtections.hop.contentId(sealBytes),
            fileId = manifest.fileId,
            targetDevice = device,
            kind = kind,
            sealBytes = sealBytes,
            key = key,
            totalBytes = manifest.totalBytes,
            status = FileSealStatus.SENDING,
            createdAt = now,
            expiresAt = now + ctx.routerConfig.value.fileTransferTtl,
        )
        sealStore.insert(seal)
        val shape = TransferShape(ctx.fileLimits.value.chunkSizeBytes, byteFrom, manifest.totalBytes)
        engine.start(newTransfer(seal, device, shape))
        return true
    }

    private fun newTransfer(seal: OutgoingSeal, peer: PeerId, shape: TransferShape) = OutgoingTransfer(
        transferId = TransferId(Uuid.random()),
        sealId = seal.sealId,
        relayTransferId = null,
        peer = peer,
        shape = shape,
        nextChunkIndex = 0,
        contiguousAcked = -1,
        offerPacketId = null,
        status = FileTransferStatus.OFFERED,
        createdAt = ctx.clock.now(),
        expiresAt = seal.expiresAt,
    )

    private suspend fun endLiveTransfers(seal: OutgoingSeal) {
        transferStore.bySeal(seal.sealId).filter { it.status.isLive }.forEach { engine.cancel(it.transferId) }
    }

    // --- account-level reporting ------------------------------------------------------------

    private val reported = mutableMapOf<Pair<FileId, AccountId>, FileSendStatus>()

    private suspend fun report(seal: OutgoingSeal) {
        if (seal.kind != FileSealKind.PUSH) return
        val account = ctx.devices.getAccountIdForDevice(seal.targetDevice) ?: return
        report(seal.fileId, account)
    }

    /**
     * Emits [account]'s aggregate for [fileId] when it changed since the last report: progress
     * while sending, then hand-off, delivery or the failure that ended every device's seal.
     */
    private suspend fun report(fileId: FileId, account: AccountId) {
        val accountSeals = pushesByAccount(fileId)[account] ?: return
        val state = aggregate(accountSeals)
        val changed = mutex.withLock { reported.put(fileId to account, state.status) != state.status }
        val event = when (state.status) {
            FileSendStatus.SENDING -> FileEvent.OutgoingProgress(fileId, account, state.sentBytes, state.totalBytes)
            FileSendStatus.HANDED_OFF -> if (changed) FileEvent.OutgoingHandedOff(fileId, account) else null
            FileSendStatus.COMPLETED -> if (changed) FileEvent.OutgoingComplete(fileId, account) else null
            else -> if (changed) FileEvent.Failed(fileId, account, failureReason(state.status)) else null
        }
        event?.let { events.emit(it) }
    }

    private suspend fun pushesByAccount(fileId: FileId): Map<AccountId, List<Pair<OutgoingSeal, List<OutgoingTransfer>>>> {
        val byAccount = mutableMapOf<AccountId, MutableList<Pair<OutgoingSeal, List<OutgoingTransfer>>>>()
        for (seal in sealStore.byFile(fileId)) {
            if (seal.kind != FileSealKind.PUSH) continue
            val account = ctx.devices.getAccountIdForDevice(seal.targetDevice) ?: continue
            byAccount.getOrPut(account) { mutableListOf() } += seal to transferStore.bySeal(seal.sealId)
        }
        return byAccount
    }

    /**
     * An account's send state over its devices' seals: delivered to any device wins; otherwise
     * sending while any seal is, handed off when the rest are; failed only when every seal ended.
     * Progress is the least any device has covered.
     */
    private fun aggregate(accountSeals: List<Pair<OutgoingSeal, List<OutgoingTransfer>>>): FileSendState {
        val total = accountSeals.first().first.totalBytes
        val statuses = accountSeals.map { it.first.status }
        val status = when {
            FileSealStatus.DELIVERED in statuses -> FileSendStatus.COMPLETED
            FileSealStatus.SENDING in statuses -> FileSendStatus.SENDING
            FileSealStatus.HANDED_OFF in statuses -> FileSendStatus.HANDED_OFF
            FileSealStatus.ENDED in statuses -> FileSendStatus.ENDED
            FileSealStatus.EXPIRED in statuses -> FileSendStatus.EXPIRED
            FileSealStatus.DECLINED in statuses -> FileSendStatus.DECLINED
            else -> FileSendStatus.CANCELLED
        }
        val sent = accountSeals.minOf { (seal, sealTransfers) -> coveredBytes(seal, sealTransfers) }
        return FileSendState(status, sent, total)
    }

    private fun coveredBytes(seal: OutgoingSeal, sealTransfers: List<OutgoingTransfer>): Long {
        if (seal.status == FileSealStatus.DELIVERED || seal.status == FileSealStatus.HANDED_OFF) return seal.totalBytes
        return sealTransfers.filter { it.peer == seal.targetDevice }.maxOfOrNull {
            it.shape.chunkOffset(it.contiguousAcked + 1).coerceAtMost(it.shape.byteTo)
        } ?: 0L
    }

    private fun failureReason(status: FileSendStatus): FileFailureReason = when (status) {
        FileSendStatus.EXPIRED -> FileFailureReason.EXPIRED
        FileSendStatus.DECLINED -> FileFailureReason.DECLINED
        FileSendStatus.CANCELLED -> FileFailureReason.CANCELLED
        else -> FileFailureReason.ENDED
    }

    private suspend fun peerDevices(target: AccountId): List<PeerId> =
        ctx.devices.getAllPeerDevicesForAccount(target).filter { it != ctx.localDeviceId }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Seal registry $what failed",
                throwable = e,
            )
        }
    }
}
