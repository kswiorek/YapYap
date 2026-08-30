package org.yapyap.routing.ping

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.router.RouterConfig
import kotlin.uuid.Uuid

//TODO: PingProvider
class PingProvider(
    private val config: StateFlow<RouterConfig>,
    private val pingPayloadFlow: MutableSharedFlow<Map<RoomId, Long>>
) {
    val sentPings: List<Uuid> = mutableListOf()

    suspend fun runIn(scope: CoroutineScope) {
        //job to send pings periodically depending on config
    }

    suspend fun handlePing(ping: SystemPayload.Ping) {
        pingPayloadFlow.emit(ping.roomLamports)

        if (ping.pingId in sentPings) {
            //is a reply to ping
            //remove from sentPings
        } else {
            //is a new ping, send reply
        }
    }

    suspend fun sendPing() {
        //generate uuid and take lamportSnapshot
    }
}