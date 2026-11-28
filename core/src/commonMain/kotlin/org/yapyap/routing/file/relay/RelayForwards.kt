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
import org.yapyap.routing.file.fileGuarded
import org.yapyap.routing.file.transfer.TransferEngine
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingStore
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.routing.store.OutgoingTransfer
import kotlin.uuid.Uuid

/**
 * The relay's onward half (docs/envelope-redesign.md §5.4): once a relay hop is whole, one
 * forward — an ordinary transfer of the same seal and shape to the final target, its chunks read
 * from the stored ciphertext. However the forward ends, the relay forgets the hop: a finished one
 * is delivered, and anything else is the pull path's to recover (forwards never re-deposit — one
 * hop, ever).
 */
internal class RelayForwards(
    private val ctx: RoutingContext,
    private val incomingStore: FileIncomingStore,
    private val transferStore: FileTransferStore,
    private val engine: TransferEngine,
) {
    private val mutex = Mutex()

    /** Relay hop [hopId] became whole: start its forward (at most one). */
    suspend fun onWhole(hopId: TransferId) = mutex.withLock {
        val hop = incomingStore.relayHop(hopId)?.takeIf { it.isWhole } ?: return@withLock
        if (transferStore.bySourceTransfer(hopId).isNotEmpty()) return@withLock
        val transfer = hop.transfer
        engine.start(
            OutgoingTransfer(
                transferId = TransferId(Uuid.random()),
                sealId = transfer.sealId,
                sourceTransferId = hopId,
                peer = transfer.finalTarget,
                shape = transfer.shape,
                nextChunkIndex = 0,
                contiguousAcked = -1,
                offerPacketId = null,
                status = FileTransferStatus.OFFERED,
                createdAt = ctx.clock.now(),
                expiresAt = transfer.expiresAt,
            ),
        )
        AppLog.debug(
            component = LogComponent.FILE,
            event = LogEvent.FILE_FORWARDED,
            message = "Forwarding a whole relay hop",
            fields = mapOf("hopId" to hopId, "finalTarget" to transfer.finalTarget),
        )
    }

    /**
     * Forgets relay hop [hopId]: a live forward is cancelled first, and its end calls this again;
     * once no forward runs, the forward rows and the hop (its stored ciphertext with it) go. The
     * ciphertext is never deleted under a running forward, which reads its chunks from it.
     */
    suspend fun drop(hopId: TransferId) = mutex.withLock {
        val forwards = transferStore.bySourceTransfer(hopId)
        val live = forwards.filter { it.status.isLive }
        if (live.isNotEmpty()) {
            live.forEach { engine.cancel(it.transferId) }
            return@withLock
        }
        forwards.forEach { transferStore.delete(it.transferId) }
        incomingStore.deleteTransfer(hopId)
    }

    /**
     * Forgets a relay hop when its forward ends, and sweeps: expired hops and idle partial ones go
     * (the idle TTL is what frees the quota of a hop its origin never finished), and a whole hop
     * with no forward (a restart cut it short) gets one. Subscribed before this
     * returns, so it hears every forward the engine runs from then on.
     */
    fun runIn(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { event ->
                val hopId = event.transfer.sourceTransferId ?: return@collect
                if (event is TransferEvent.Ended) guarded("forward end") { drop(hopId) }
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
        for (hop in incomingStore.relayHops()) {
            val hopId = hop.transfer.transferId
            val forwards = transferStore.bySourceTransfer(hopId)
            when {
                now >= hop.transfer.expiresAt -> drop(hopId)
                !hop.isWhole && hop.lastStoredAt + idleTtl <= now -> drop(hopId)
                // Its forward ended while nobody listened (a restart): forget it now.
                forwards.isNotEmpty() && forwards.none { it.status.isLive } -> drop(hopId)
                hop.isWhole -> onWhole(hopId)
                else -> Unit
            }
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        fileGuarded("Relay forwards $what failed", block = block)
    }
}
