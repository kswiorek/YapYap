package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId

data class WebRtcSignal(
    val source: PeerId,
    val target: PeerId,
    val payload: ByteArray,
)

interface WebRtcTransport {
    val incomingSignals: Flow<WebRtcSignal>

    suspend fun start(localPeer: PeerDescriptor)

    suspend fun stop()

    suspend fun sendSignal(signal: WebRtcSignal)
}

