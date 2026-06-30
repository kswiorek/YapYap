package org.yapyap.transport.webrtc.backend

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.PeerId
import org.yapyap.transport.webrtc.types.WebRtcAvChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcSessionEvent
import org.yapyap.transport.webrtc.types.WebRtcSignal

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

