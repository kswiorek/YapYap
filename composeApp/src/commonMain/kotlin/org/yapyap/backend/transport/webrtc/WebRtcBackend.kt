package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.DeviceAddress
import org.yapyap.backend.transport.webrtc.types.AvControlUpdate
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

data class WebRtcIncomingDataFrame(
    val sessionId: String,
    val source: DeviceAddress,
    val payload: ByteArray,
)

sealed interface WebRtcSessionEvent {
    data class Connecting(
        val sessionId: String,
        val peer: DeviceAddress,
    ) : WebRtcSessionEvent

    data class Connected(
        val sessionId: String,
        val peer: DeviceAddress,
    ) : WebRtcSessionEvent

    data class Closed(
        val sessionId: String,
        val peer: DeviceAddress,
    ) : WebRtcSessionEvent

    data class Failed(
        val sessionId: String,
        val peer: DeviceAddress,
        val reason: String,
    ) : WebRtcSessionEvent
}

sealed interface WebRtcAvSessionEvent {
    data class Negotiating(
        val sessionId: String,
        val peer: DeviceAddress,
        val options: AvSessionOptions? = null,
    ) : WebRtcAvSessionEvent

    data class Active(
        val sessionId: String,
        val peer: DeviceAddress,
        val options: AvSessionOptions? = null,
    ) : WebRtcAvSessionEvent

    data class Ended(
        val sessionId: String,
        val peer: DeviceAddress,
    ) : WebRtcAvSessionEvent

    data class Failed(
        val sessionId: String,
        val peer: DeviceAddress,
        val reason: String,
    ) : WebRtcAvSessionEvent

    data class Rejected(
        val sessionId: String,
        val peer: DeviceAddress,
        val reason: String? = null,
    ) : WebRtcAvSessionEvent
}

interface WebRtcBackend {
    val outgoingSignals: Flow<WebRtcSignal>
    val incomingDataFrames: Flow<WebRtcIncomingDataFrame>
    val sessionEvents: Flow<WebRtcSessionEvent>
    val avSessionEvents: Flow<WebRtcAvSessionEvent>

    suspend fun start(localDevice: DeviceAddress)

    suspend fun stop()

    suspend fun openSession(target: DeviceAddress, sessionId: String)

    suspend fun handleRemoteSignal(signal: WebRtcSignal)

    suspend fun closeSession(sessionId: String)

    suspend fun sendData(sessionId: String, target: DeviceAddress, payload: ByteArray)

    suspend fun openAvSession(target: DeviceAddress, sessionId: String, options: AvSessionOptions)

    suspend fun acceptAvSession(sessionId: String, options: AvSessionOptions)

    suspend fun rejectAvSession(sessionId: String, reason: String)

    suspend fun updateAvControls(sessionId: String, update: AvControlUpdate)

    suspend fun closeAvSession(sessionId: String)
}

