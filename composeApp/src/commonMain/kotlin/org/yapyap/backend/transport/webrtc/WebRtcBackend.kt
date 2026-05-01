package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

enum class WebRtcDataType {
    ENVELOPE_BINARY,
    AV_DATA,
}

data class WebRtcDataFrame(
    val sessionId: String,
    val source: String,
    val target: String,
    val dataType: WebRtcDataType,
    val payload: ByteArray,
)

sealed interface WebRtcSessionEvent {
    data class Connecting(val sessionId: String, val peer: String) : WebRtcSessionEvent
    data class Connected(val sessionId: String, val peer: String) : WebRtcSessionEvent
    data class Closed(val sessionId: String, val peer: String) : WebRtcSessionEvent
    data class Failed(val sessionId: String, val peer: String, val reason: String) : WebRtcSessionEvent
}
sealed interface WebRtcAvChannelEvent {
    data class Adding(val sessionId: String, val peer: String) : WebRtcAvChannelEvent
    data class Active(val sessionId: String, val peer: String) : WebRtcAvChannelEvent
    data class Removed(val sessionId: String, val peer: String) : WebRtcAvChannelEvent
    data class Failed(val sessionId: String, val peer: String, val reason: String) : WebRtcAvChannelEvent
}

interface WebRtcBackend {
    val outgoingSignals: Flow<WebRtcSignal>
    val incomingDataFrames: Flow<WebRtcDataFrame>
    val sessionEvents: Flow<WebRtcSessionEvent>
    val avChannelEvents: Flow<WebRtcAvChannelEvent>

    suspend fun start(localDevice: String)

    suspend fun stop()

    suspend fun openSession(target: String, sessionId: String)

    suspend fun handleRemoteSignal(signal: WebRtcSignal)

    suspend fun closeSession(sessionId: String)

    suspend fun sendData(dataFrame: WebRtcDataFrame)

    suspend fun addAvChannel(sessionId: String)
    suspend fun removeAvChannel(sessionId: String)
}

