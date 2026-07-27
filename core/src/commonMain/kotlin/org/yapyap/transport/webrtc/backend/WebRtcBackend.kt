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

    suspend fun openSession(target: PeerId)

    suspend fun handleRemoteSignal(signal: WebRtcSignal)

    fun hasSession(target: PeerId): Boolean

    suspend fun closeSession(target: PeerId)

    suspend fun sendData(dataFrame: WebRtcDataFrame)

    suspend fun addAvChannel(target: PeerId)
    suspend fun removeAvChannel(target: PeerId)
}

