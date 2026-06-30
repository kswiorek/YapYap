package org.yapyap.transport.webrtc.backend

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.PeerId
import org.yapyap.transport.webrtc.types.WebRtcSignal

enum class WebRtcDataType {
    ENVELOPE_BINARY,
    AV_DATA,
}

data class WebRtcDataFrame(
    val sessionId: String,
    val source: PeerId,
    val target: PeerId,
    val dataType: WebRtcDataType,
    val payload: ByteArray,
)

sealed interface WebRtcSessionEvent {
    val sessionId: String
    val peer: PeerId
    data class Connecting(override val sessionId: String, override val peer: PeerId) : WebRtcSessionEvent
    data class Connected(override val sessionId: String, override val peer: PeerId) : WebRtcSessionEvent
    data class Closed(override val sessionId: String, override val peer: PeerId) : WebRtcSessionEvent
    data class Failed(override val sessionId: String, override val peer: PeerId, val reason: String) : WebRtcSessionEvent
}
sealed interface WebRtcAvChannelEvent {
    val sessionId: String
    val peer: PeerId
    data class Adding(override val sessionId: String, override val peer: PeerId) : WebRtcAvChannelEvent
    data class Active(override val sessionId: String, override val peer: PeerId) : WebRtcAvChannelEvent
    data class Removed(override val sessionId: String, override val peer: PeerId) : WebRtcAvChannelEvent
    data class Failed(override val sessionId: String, override val peer: PeerId, val reason: String) : WebRtcAvChannelEvent
}

interface WebRtcBackend {
    val outgoingSignals: Flow<WebRtcSignal>
    val incomingDataFrames: Flow<WebRtcDataFrame>
    val sessionEvents: Flow<WebRtcSessionEvent>
    val avChannelEvents: Flow<WebRtcAvChannelEvent>

    suspend fun start(localDevice: PeerId)

    suspend fun stop()

    suspend fun openSession(target: PeerId, sessionId: String)

    suspend fun handleRemoteSignal(signal: WebRtcSignal)

    suspend fun closeSession(sessionId: String)

    suspend fun sendData(dataFrame: WebRtcDataFrame)

    suspend fun addAvChannel(sessionId: String)
    suspend fun removeAvChannel(sessionId: String)
}

