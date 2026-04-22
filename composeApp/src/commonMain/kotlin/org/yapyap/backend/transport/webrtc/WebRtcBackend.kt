package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId

data class WebRtcIncomingDataFrame(
    val sessionId: String,
    val source: PeerId,
    val payload: ByteArray,
)

sealed interface WebRtcSessionEvent {
    data class Connecting(
        val sessionId: String,
        val peer: PeerId,
    ) : WebRtcSessionEvent

    data class Connected(
        val sessionId: String,
        val peer: PeerId,
    ) : WebRtcSessionEvent

    data class Closed(
        val sessionId: String,
        val peer: PeerId,
    ) : WebRtcSessionEvent

    data class Failed(
        val sessionId: String,
        val peer: PeerId,
        val reason: String,
    ) : WebRtcSessionEvent
}

interface WebRtcBackend {
    val outgoingSignals: Flow<WebRtcSignal>
    val incomingDataFrames: Flow<WebRtcIncomingDataFrame>
    val sessionEvents: Flow<WebRtcSessionEvent>

    suspend fun start(localPeer: PeerDescriptor)

    suspend fun stop()

    suspend fun openSession(target: PeerId, sessionId: String)

    suspend fun handleRemoteSignal(signal: WebRtcSignal)

    suspend fun closeSession(sessionId: String)

    suspend fun sendData(sessionId: String, target: PeerId, payload: ByteArray)
}

