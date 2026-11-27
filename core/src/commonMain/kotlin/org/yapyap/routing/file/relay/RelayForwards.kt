package org.yapyap.routing.file.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.TransferId
import org.yapyap.routing.file.TransferEvent
import org.yapyap.routing.file.transfer.TransferEngine
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.routing.store.OutgoingTransfer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * The relay's onward half (docs/envelope-redesign.md §5.4): once a deposited transfer is whole,
 * one forward — a new transfer of the same seal and shape to the final target, from the stored
 * ciphertext. However the forward ends, the relay forgets the deposit: a finished one is delivered,
 * and anything else is the pull path's to recover (forwards never re-deposit — one hop, ever).
 */
internal class RelayForwards(
    private val ctx: RoutingContext,
    private val relayStore: FileRelayStore,
    private val transferStore: FileTransferStore,
    private val engine: TransferEngine,
) {
    private val mutex = Mutex()

    /** [relayTransferId] became whole: start its forward (at most one). */
    suspend fun onWhole(relayTransferId: TransferId) = mutex.withLock {
        val relay = relayStore.find(relayTransferId) ?: return@withLock
        if (relay.status != FileRelayStatus.WHOLE) return@withLock
        if (transferStore.byRelayTransfer(relayTransferId).isNotEmpty()) return@withLock
        engine.start(
            OutgoingTransfer(
                transferId = TransferId(Uuid.random()),
                sealId = relay.sealId,
                relayTransferId = relayTransferId,
                peer = relay.finalTarget,
                shape = relay.shape,
                nextChunkIndex = 0,
                contiguousAcked = -1,
                offerPacketId = null,
                status = FileTransferStatus.OFFERED,
                createdAt = ctx.clock.now(),
                expiresAt = relay.expiresAt,
            ),
        )
        AppLog.debug(
            component = LogComponent.FILE,
            event = LogEvent.FILE_FORWARDED,
            message = "Forwarding a whole deposit",
            fields = mapOf("relayTransferId" to relayTransferId, "finalTarget" to relay.finalTarget),
        )
    }

    /** Forgets a deposit: its forward stops and its stored ciphertext goes. */
    suspend fun drop(relayTransferId: TransferId) = mutex.withLock {
        for (forward in transferStore.byRelayTransfer(relayTransferId)) {
            if (forward.status.isLive) engine.cancel(forward.transferId)
            transferStore.delete(forward.transferId)
        }
        relayStore.delete(relayTransferId)
    }

    /**
     * Forgets a deposit when its forward ends, and sweeps: expired deposits and idle partial ones
     * go (the idle TTL is what frees the quota of a deposit its origin never finished), and a
     * whole deposit with no forward (a restart cut it short) gets one. Subscribed before this
     * returns, so it hears every forward the engine runs from then on.
     */
    fun runIn(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { event ->
                val relayTransferId = event.transfer.relayTransferId ?: return@collect
                if (event is TransferEvent.Ended) guarded("forward end") { drop(relayTransferId) }
            }
        }
        while (true) {
            guarded("sweep") { sweep() }
            delay(ctx.routerConfig.value.fileSweepInterval)
        }
    }

    private suspend fun sweep() {
        val now = ctx.clock.now()
        val idleTtl = ctx.routerConfig.value.fileRelayIdleTtl
        for (relay in relayStore.all()) {
            val forwards = transferStore.byRelayTransfer(relay.transferId)
            when {
                now >= relay.expiresAt -> drop(relay.transferId)
                relay.status == FileRelayStatus.RESERVED && relay.lastChunkAt + idleTtl <= now -> drop(relay.transferId)
                // Its forward ended while nobody listened (a restart): forget it now.
                forwards.isNotEmpty() && forwards.none { it.status.isLive } -> drop(relay.transferId)
                relay.status == FileRelayStatus.WHOLE -> onWhole(relay.transferId)
                else -> Unit
            }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Relay forwards $what failed",
                throwable = e,
            )
        }
    }
}
