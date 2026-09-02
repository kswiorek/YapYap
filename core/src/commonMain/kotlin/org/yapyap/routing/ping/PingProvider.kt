package org.yapyap.routing.ping

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload.Ping
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import kotlin.uuid.Uuid

internal class PingProvider(
    private val ctx: RoutingContext,
    private val config: StateFlow<RouterConfig>,
    private val pingPayloadFlow: MutableSharedFlow<List<Pair<RoomId, Long>>>,
    private val lamportSnapshotProvider: LamportSnapshotProvider,
    private val systemSender: SystemSender,
    /** Invoked after an originating probe is actually transmitted, so the caller (availability
     *  tracking) knows this peer received a real attempt and a silent response is meaningful. */
    private val onPingTransmitted: ((PeerId) -> Unit)? = null,
) {
    private var pingLoopJob: Job? = null

    fun runIn(scope: CoroutineScope) {
        pingLoopJob = scope.launch {
            config.map { it.pingInterval }
                .distinctUntilChanged()
                .collectLatest { interval ->
                    while (isActive) {
                        delay(interval)
                        // Best-effort: a transient send failure to one peer must not kill the
                        // heartbeat loop permanently.
                        runCatching { ping() }
                    }
                }
        }
    }

    fun stop() {
        pingLoopJob?.cancel()
        pingLoopJob = null
    }

    suspend fun ping() = fanOutToAllPeers(
        noPeersEvent = LogEvent.PING_NO_PEERS,
        noPeersMessage = "No peers to ping",
    ) { sendPing(it) }

    suspend fun logOff() = fanOutToAllPeers(
        noPeersEvent = LogEvent.PING_NO_PEERS,
        noPeersMessage = "No peers to notify of log-off",
    ) { sendLogOff(it) }

    /**
     * Fans a best-effort action out to every known peer. Failures to individual peers are caught
     * and swallowed so that one unreachable device cannot cancel the sends to the remaining peers
     * (and cannot, for example, abort the rest of a router shutdown from [logOff]).
     */
    private suspend fun fanOutToAllPeers(
        noPeersEvent: LogEvent,
        noPeersMessage: String,
        block: suspend (PeerId) -> Unit,
    ) {
        val peers = ctx.identityResolver.getAllPeers().filter { it != ctx.localDeviceId }
        if (peers.isEmpty()) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = noPeersEvent,
                message = noPeersMessage,
            )
            return
        }
        supervisorScope {
            peers.map { peer -> async { runCatching { block(peer) } } }.awaitAll()
        }
    }

    /**
     * Handles an inbound ping from [peerId]. Its lamport snapshot is always forwarded (both a probe
     * and a reply carry the sender's latest clocks, which is what triggers range sync). Only a fresh
     * probe ([Ping.isReply] == false) is answered — a reply is never re-echoed, so even a delayed or
     * duplicated ping cannot start an echo loop.
     */
    suspend fun handlePing(peerId: PeerId, ping: Ping) {
        pingPayloadFlow.emit(ping.roomLamports)

        if (!ping.isReply) {
            // A new probe from [peerId]: echo it back so they can correlate.
            sendPing(peerId, ping.pingId)
        }
    }

    /** Sends an original probe to [peerId] (or, when [echoPingId] is set, an echo carrying that id). */
    private suspend fun sendPing(peerId: PeerId, echoPingId: Uuid? = null) {
        val ping = Ping(
            pingId = echoPingId ?: Uuid.random(),
            isReply = echoPingId != null,
            roomLamports = lamportSnapshotProvider.latestRoomLamports(peerId),
        )
        systemSender.sendPing(peerId, ping)
        // Only an originating probe (not an echo) is a real liveness attempt we want tracked.
        if (echoPingId == null) onPingTransmitted?.invoke(peerId)
    }

    private suspend fun sendLogOff(peerId: PeerId) {
        systemSender.sendLogOff(peerId)
    }
}