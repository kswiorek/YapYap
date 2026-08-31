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

//TODO: PingProvider
internal class PingProvider(
    private val ctx: RoutingContext,
    private val config: StateFlow<RouterConfig>,
    private val pingPayloadFlow: MutableSharedFlow<List<Pair<RoomId, Long>>>,
    private val lamportSnapshotProvider: LamportSnapshotProvider,
    private val systemSender: SystemSender,
) {
    val sentPings = mutableListOf<Uuid>()

    private var pingLoopJob: Job? = null

    fun runIn(scope: CoroutineScope) {
        pingLoopJob = scope.launch {
            config.map { it.pingInterval }
                .distinctUntilChanged()
                .collectLatest { interval ->
                    while (isActive) { delay(interval); ping() }
                }
        }
    }

    fun stop() {
        pingLoopJob?.cancel()
        pingLoopJob = null
    }

    suspend fun ping() {
        val peers = ctx.identityResolver.getAllPeers()
        if (peers.isEmpty()) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.PING_NO_PEERS,
                message = "No peers to ping",
            )
        }
        coroutineScope {
            peers.map { peer ->
                async {
                    sendPing(peer)
                }
            }.awaitAll()
        }
    }

    suspend fun logOff() {
        val peers = ctx.identityResolver.getAllPeers()
        if (peers.isEmpty()) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.PING_NO_PEERS,
                message = "No peers to ping",
            )
        }
        coroutineScope {
            peers.map { peer ->
                async {
                    sendLogOff(peer)
                }
            }.awaitAll()
        }
    }

    suspend fun handlePing(peerId: PeerId, ping: Ping) {
        pingPayloadFlow.emit(ping.roomLamports)

        if (ping.pingId in sentPings) {
            sentPings.remove(ping.pingId)
            //is a reply to ping
            //remove from sentPings
        } else {
            //is a new ping, send reply
            sendPing(peerId, ping.pingId)
        }
    }

    private suspend fun sendPing(peerId: PeerId, pingId: Uuid? = null) {
        //generate uuid and take lamportSnapshot
        val ping = Ping(
            pingId = pingId?: Uuid.random(),
            roomLamports = lamportSnapshotProvider.latestRoomLamports(peerId),
        )
        systemSender.sendPing(peerId, ping)
    }

    private suspend fun sendLogOff(peerId: PeerId) {
        systemSender.sendLogOff(peerId)
    }
}