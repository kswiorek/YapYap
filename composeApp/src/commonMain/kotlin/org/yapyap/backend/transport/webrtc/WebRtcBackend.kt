package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.transport.webrtc.types.AvControlUpdate
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

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

sealed interface WebRtcAvSessionEvent {
    data class Negotiating(
        val sessionId: String,
        val peer: PeerId,
        val options: AvSessionOptions? = null,
    ) : WebRtcAvSessionEvent

    data class Active(
        val sessionId: String,
        val peer: PeerId,
        val options: AvSessionOptions? = null,
    ) : WebRtcAvSessionEvent

    data class Ended(
        val sessionId: String,
        val peer: PeerId,
    ) : WebRtcAvSessionEvent

    data class Failed(
        val sessionId: String,
        val peer: PeerId,
        val reason: String,
    ) : WebRtcAvSessionEvent

    data class Rejected(
        val sessionId: String,
        val peer: PeerId,
        val reason: String? = null,
    ) : WebRtcAvSessionEvent
}

interface WebRtcBackend {
    val outgoingSignals: Flow<WebRtcSignal>
    val incomingDataFrames: Flow<WebRtcIncomingDataFrame>
    val sessionEvents: Flow<WebRtcSessionEvent>
    val avSessionEvents: Flow<WebRtcAvSessionEvent>

    suspend fun start(localPeer: PeerDescriptor)

    suspend fun stop()

    suspend fun openSession(target: PeerId, sessionId: String)

    suspend fun handleRemoteSignal(signal: WebRtcSignal)

    suspend fun closeSession(sessionId: String)

    suspend fun sendData(sessionId: String, target: PeerId, payload: ByteArray)

    suspend fun openAvSession(target: PeerId, sessionId: String, options: AvSessionOptions)

    suspend fun acceptAvSession(sessionId: String, options: AvSessionOptions)

    suspend fun rejectAvSession(sessionId: String, reason: String)

    suspend fun updateAvControls(sessionId: String, update: AvControlUpdate)

    suspend fun closeAvSession(sessionId: String)
}

