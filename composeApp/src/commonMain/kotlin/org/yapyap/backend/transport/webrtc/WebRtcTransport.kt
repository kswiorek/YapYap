package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.transport.webrtc.types.AvControlUpdate
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcAvSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcIncomingAvSessionRequest
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

interface WebRtcTransport {
    val incomingData: Flow<WebRtcIncomingDataFrame>
    val outgoingSignals: Flow<WebRtcSignal>
    val sessionStates: Flow<WebRtcSessionState>
    val incomingAvSessionRequests: Flow<WebRtcIncomingAvSessionRequest>
    val avSessionStates: Flow<WebRtcAvSessionState>

    suspend fun start(deviceId: String)

    suspend fun stop()

    suspend fun initiateSession(target: String, sessionId: String)

    suspend fun sendData(sessionId: String, target: String, payload: ByteArray)

    suspend fun closeSession(sessionId: String)

    suspend fun handleInboundSignal(signal: WebRtcSignal, receivedAtEpochSeconds: Long)

    suspend fun initiateAvSession(target: String, sessionId: String, options: AvSessionOptions)

    suspend fun acceptAvSession(sessionId: String, options: AvSessionOptions)

    suspend fun rejectAvSession(sessionId: String, reason: String)

    suspend fun updateAvControls(sessionId: String, update: AvControlUpdate)

    suspend fun endAvSession(sessionId: String)
}

