package org.yapyap.routing.file.seal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
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
 *
 * The mutex only plans: it decides what needs a seal (and reserves it in [inFlight]), which
 * transfers to cancel, or which relays a deposit goes to. The expensive part — crypto, store
 * inserts, `TransferEngine.start` and `TransferEngine.cancel` — runs outside it in guarded launches
 * (one per device, relay or transfer, joined before the entry point returns), so a multi-device
 * push never blocks every serve, COMPLETE and settle behind it.
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
    /** Serialises seal decisions, so an account's aggregate is computed once per change. */
    private val mutex = Mutex()

    /**
     * (file, device) pairs whose seal is being made right now, each with its lease. A second
     * push/serve of the same pair plans nothing: the live-seal check cannot see a row that has not
     * landed yet. A lease is released only by the operation that took it.
     */
    private val inFlight = mutableMapOf<Pair<FileId, PeerId>, Lease>()

    /** Identity token for one in-flight seal; the map guards that only its taker releases it. */
    private class Lease

    /** One seal to make outside the mutex, for one target device. */
    private data class SealPlan(
        val manifest: FileManifest,
        val device: PeerId,
        val kind: FileSealKind,
        val byteFrom: Long,
        val lease: Lease,
    ) {
        val key: Pair<FileId, PeerId> get() = manifest.fileId to device
    }

    /** The serve decision taken under the mutex. */
    private sealed interface ServePlan {
        /** A serve of the same file to the device is already under way: answer success. */
        data object AlreadyServing : ServePlan

        /** Make the seal; these transfers are superseded and end first. */
        data class Make(val plan: SealPlan, val cancel: List<TransferId>) : ServePlan
    }

    /** The deposit plan taken under the mutex: the claimed round, its relays and covered shape. */
    private data class DepositPlan(
        val seal: OutgoingSeal,
        val relays: List<PeerId>,
        val shape: TransferShape,
        val byteFrom: Long,
    )

    /**
     * Seals [fileId] for every device of [target] that has no live seal of it and pushes it.
     * A held file above the network's small-file limit is a silent no-op (large files are
     * pull-only); a file this device does not hold still reports Failed as before.
     */
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
        if (manifest.totalBytes > ctx.routerConfig.value.smallFileMaxBytes) return
        val devices = peerDevices(target)
        val plans = mutex.withLock {
            val planned = mutableListOf<SealPlan>()
            for (device in devices) {
                val key = fileId to device
                if (key in inFlight) continue
                val live =
                    sealStore.byFileAndTarget(fileId, device).any { it.kind == FileSealKind.PUSH && it.status.isLive }
                if (live) continue
                planned += SealPlan(manifest, device, FileSealKind.PUSH, byteFrom = 0, lease = Lease())
            }
            // Mark only after every suspending read: a cancellation mid-plan leaves no leases.
            planned.forEach { inFlight[it.key] = it.lease }
            planned
        }
        makeSeals(plans)
        report(fileId, target)
    }

    /**
     * Serves [fileId] to [device] from the chunk boundary at or below [fromOffset], with a fresh
     * seal. A REQUEST repeated within one attempt is answered by the serve already under way, and
     * any other live transfer of the file to that device is cancelled: one transfer of a file
     * from us to one device at a time. A request that arrives while a seal for the same pair is
     * still being made is declined: that in-flight seal (push or serve) covers the device.
     */
    suspend fun serve(fileId: FileId, device: PeerId, fromOffset: Long): Boolean {
        val manifest = plaintext.describe(fileId) ?: return false
        if (manifest.totalBytes > ctx.routerConfig.value.smallFileMaxBytes) return false
        val chunkSize = ctx.fileLimits.value.chunkSizeBytes
        val byteFrom = (fromOffset.coerceIn(0, manifest.totalBytes) / chunkSize) * chunkSize
        val key = fileId to device
        val started = when (
            val decision = mutex.withLock {
                if (key in inFlight) return@withLock null
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
                if (underWay) return@withLock ServePlan.AlreadyServing
                val lease = Lease()
                inFlight[key] = lease
                ServePlan.Make(
                    plan = SealPlan(manifest, device, FileSealKind.SERVE, byteFrom, lease),
                    cancel = direct.map { it.second.transferId },
                )
            }
        ) {
            null -> return false
            ServePlan.AlreadyServing -> return true
            is ServePlan.Make -> {
                val plan = decision.plan
                try {
                    cancelTransfers(decision.cancel)
                    makeSeal(plan)
                } finally {
                    release(plan)
                }
            }
        }
        if (!started) return false
        ctx.devices.getAccountIdForDevice(device)?.let { events.emit(FileEvent.ServeStarted(fileId, it)) }
        return true
    }

    /** User cancel: every live push seal of [fileId] to [target]'s devices ends with its transfers. */
    suspend fun cancel(fileId: FileId, target: AccountId) {
        val devices = peerDevices(target)
        val toCancel = mutex.withLock {
            val ids = mutableListOf<TransferId>()
            for (device in devices) {
                for (seal in sealStore.byFileAndTarget(fileId, device)) {
                    if (seal.kind != FileSealKind.PUSH || !seal.status.isLive) continue
                    sealStore.setStatus(seal.sealId, FileSealStatus.CANCELLED)
                    ids += liveTransferIds(seal.sealId)
                }
            }
            ids
        }
        cancelTransfers(toCancel)
        report(fileId, target)
    }

    /** The seal's target holds the file. */
    suspend fun onComplete(sealId: ContentId, from: PeerId) {
        val seal = sealStore.find(sealId) ?: return
        if (from != seal.targetDevice) return
        val toCancel = mutex.withLock {
            val current = sealStore.find(sealId) ?: return@withLock emptyList()
            if (current.status == FileSealStatus.DELIVERED) {
                emptyList()
            } else {
                sealStore.setStatus(sealId, FileSealStatus.DELIVERED)
                liveTransferIds(sealId)
            }
        }
        cancelTransfers(toCancel)
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
            delay(ctx.routerConfig.value.fileSweepInterval)
        }
    }

    private suspend fun onTransferEvent(event: TransferEvent) {
        val seal = sealStore.find(event.transfer.sealId) ?: return
        when (event) {
            is TransferEvent.Progress -> if (event.transfer.peer == seal.targetDevice) report(seal)
            is TransferEvent.Gone -> if (event.transfer.peer == seal.targetDevice) deposit(seal, event.transfer)
            is TransferEvent.Ended -> {
                cancelTransfers(settle(seal.sealId))
                report(seal)
            }
        }
    }

    /**
     * One deposit round per seal (claimed durably): one transfer to each relay the policy picks,
     * carrying the same seal and the remainder after the target's ACKed prefix. Relays are picked
     * before the claim, so an empty choice never burns the round; the transfers start outside the
     * lock, one guarded launch per relay.
     */
    private suspend fun deposit(seal: OutgoingSeal, direct: OutgoingTransfer) {
        val plan = mutex.withLock {
            val current = sealStore.find(seal.sealId) ?: return@withLock null
            if (current.status != FileSealStatus.SENDING) return@withLock null
            val shape = direct.shape
            val byteFrom = shape.chunkOffset(direct.contiguousAcked + 1).coerceAtMost(shape.byteTo)
            if (byteFrom >= shape.byteTo && shape.chunkCount > 0) return@withLock null
            val relays = relaySelection.selectRelays(seal.targetDevice).filter { it != seal.targetDevice }
            if (relays.isEmpty()) return@withLock null
            if (!sealStore.claimDeposit(seal.sealId, ctx.clock.now())) return@withLock null
            DepositPlan(current, relays, TransferShape(shape.chunkSizeBytes, byteFrom, shape.byteTo), byteFrom)
        } ?: return
        val started = coroutineScope {
            plan.relays.map { relay ->
                async {
                    guarded("deposit", fields = mapOf("sealId" to plan.seal.sealId, "relay" to relay)) {
                        engine.start(newTransfer(plan.seal, relay, plan.shape))
                    } != null
                }
            }.awaitAll().count { it }
        }
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_DEPOSITED,
            message = "Deposited a seal's remainder at relays",
            fields = mapOf(
                "sealId" to plan.seal.sealId,
                "relays" to plan.relays.size,
                "started" to started,
                "byteFrom" to plan.byteFrom,
            ),
        )
    }

    /**
     * Derives the seal's state from its transfers (see the class doc) and returns the transfers
     * that must end with it. The status write stays atomic with the decision; the cancellations
     * run outside the lock.
     */
    private suspend fun settle(sealId: ContentId): List<TransferId> = mutex.withLock {
        val seal = sealStore.find(sealId) ?: return@withLock emptyList()
        if (!seal.status.isLive) return@withLock emptyList()
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
        if (next == seal.status) return@withLock emptyList()
        sealStore.setStatus(sealId, next)
        when (next) {
            FileSealStatus.DELIVERED, FileSealStatus.DECLINED -> all.filter { it.status.isLive }.map { it.transferId }
            // The relays deliver the rest; pushing it directly too would only duplicate traffic.
            FileSealStatus.HANDED_OFF -> direct.filter { it.status.isLive }.map { it.transferId }
            else -> emptyList()
        }
    }

    /** Expired seals go, with their transfers; a seal still sending reports EXPIRED first. */
    private suspend fun sweep() {
        val now = ctx.clock.now()
        for (seal in sealStore.live()) cancelTransfers(settle(seal.sealId))
        for (seal in sealStore.expiredAt(now)) {
            val toCancel = mutex.withLock {
                if (seal.status == FileSealStatus.SENDING) sealStore.setStatus(seal.sealId, FileSealStatus.EXPIRED)
                liveTransferIds(seal.sealId)
            }
            cancelTransfers(toCancel)
            report(seal)
            mutex.withLock {
                transferStore.bySeal(seal.sealId).forEach { transferStore.delete(it.transferId) }
                sealStore.delete(seal.sealId)
                if (sealStore.byFile(seal.fileId).isEmpty()) reported.keys.removeAll { it.first == seal.fileId }
            }
        }
    }

    /**
     * Runs every planned seal outside the mutex, one guarded launch per device. Each child releases
     * its own lease as soon as its seal lands; the outer finally releases the whole plan's leases,
     * so a cancellation between planning and the children starting cannot strand one.
     */
    private suspend fun makeSeals(plans: List<SealPlan>) {
        try {
            coroutineScope {
                for (plan in plans) {
                    launch {
                        try {
                            makeSeal(plan)
                        } finally {
                            release(plan)
                        }
                    }
                }
            }
        } finally {
            plans.forEach { release(it) }
        }
    }

    /** Releases [plan]'s in-flight lease, but only if no later operation has taken the pair. */
    private suspend fun release(plan: SealPlan) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (inFlight[plan.key] === plan.lease) inFlight.remove(plan.key)
            }
        }
    }

    /** Makes one seal (crypto, store insert, first transfer); false when it can't be made now. */
    private suspend fun makeSeal(plan: SealPlan): Boolean =
        guarded("seal ${plan.device}") {
            createSeal(plan.manifest, plan.device, plan.kind, plan.byteFrom)
        } ?: false

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
        if (sealBytes.size > ctx.fileLimits.value.maxSealBytes) {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Seal exceeds the transport budget; the pull path covers the device",
                fields = mapOf(
                    "fileId" to manifest.fileId,
                    "device" to device,
                    "sealBytes" to sealBytes.size,
                    "maxSealBytes" to ctx.fileLimits.value.maxSealBytes,
                ),
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

    /** Ends the given transfers outside the lock, one guarded launch each. */
    private suspend fun cancelTransfers(transferIds: List<TransferId>) {
        if (transferIds.isEmpty()) return
        coroutineScope {
            for (transferId in transferIds) {
                launch {
                    guarded("cancel", fields = mapOf("transferId" to transferId)) {
                        engine.cancel(transferId)
                    }
                }
            }
        }
    }

    private suspend fun liveTransferIds(sealId: ContentId): List<TransferId> =
        transferStore.bySeal(sealId).filter { it.status.isLive }.map { it.transferId }

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

    private suspend fun <T> guarded(
        what: String,
        fields: Map<String, Any?> = emptyMap(),
        block: suspend () -> T,
    ): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Seal registry $what failed",
                throwable = e,
                fields = fields,
            )
            null
        }
    }
}
