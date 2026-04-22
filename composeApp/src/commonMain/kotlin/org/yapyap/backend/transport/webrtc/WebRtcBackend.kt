package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId

data class WebRtcIncomingDataFrame(
    val source: PeerId,
    val payload: ByteArray,
)

interface WebRtcBackend {
    val outgoingSignals: Flow<WebRtcSignal>
    val incomingDataFrames: Flow<WebRtcIncomingDataFrame>

    suspend fun start(localPeer: PeerDescriptor)

    suspend fun stop()

    suspend fun handleRemoteSignal(signal: WebRtcSignal)

    suspend fun sendData(target: PeerId, payload: ByteArray)
}

