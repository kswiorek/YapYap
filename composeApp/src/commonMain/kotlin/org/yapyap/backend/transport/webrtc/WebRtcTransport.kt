package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.transport.webrtc.types.AvControlUpdate
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcAvSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcIncomingAvSessionRequest
import org.yapyap.backend.transport.webrtc.types.WebRtcIncomingSessionRequest
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionState

interface WebRtcTransport {
    val incomingData: Flow<WebRtcIncomingDataFrame>
    val incomingSessionRequests: Flow<WebRtcIncomingSessionRequest>
    val sessionStates: Flow<WebRtcSessionState>
    val incomingAvSessionRequests: Flow<WebRtcIncomingAvSessionRequest>
    val avSessionStates: Flow<WebRtcAvSessionState>

    suspend fun start(localPeer: PeerDescriptor)

    suspend fun stop()

    suspend fun initiateSession(target: PeerId): String

    suspend fun acceptSession(sessionId: String)

    suspend fun rejectSession(sessionId: String, reason: String)

    suspend fun sendData(sessionId: String, target: PeerId, payload: ByteArray)

    suspend fun closeSession(sessionId: String)

    suspend fun initiateAvSession(target: PeerId, options: AvSessionOptions): String

    suspend fun acceptAvSession(sessionId: String, options: AvSessionOptions)

    suspend fun rejectAvSession(sessionId: String, reason: String)

    suspend fun updateAvControls(sessionId: String, update: AvControlUpdate)

    suspend fun endAvSession(sessionId: String)
}

