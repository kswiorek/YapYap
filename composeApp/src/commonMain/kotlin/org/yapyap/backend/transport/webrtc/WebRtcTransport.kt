package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId

data class WebRtcSignal(
    val sessionId: String,
    val kind: WebRtcSignalKind,
    val source: PeerId,
    val target: PeerId,
    val payload: ByteArray,
)

enum class WebRtcSignalKind(val wireValue: Byte) {
    OFFER(1),
    ANSWER(2),
    ICE(3),
    REJECT(4),
    CANCEL(5);

    companion object {
        fun fromWireValue(value: Byte): WebRtcSignalKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported WebRTC signal kind wire value: $value")
    }
}

data class WebRtcIncomingSessionRequest(
    val sessionId: String,
    val source: PeerId,
    val receivedAtEpochSeconds: Long,
)

enum class WebRtcSessionPhase {
    PENDING_DECISION,
    NEGOTIATING,
    CONNECTED,
    REJECTED,
    CLOSED,
    FAILED,
}

data class WebRtcSessionState(
    val sessionId: String,
    val peer: PeerId,
    val phase: WebRtcSessionPhase,
    val reason: String? = null,
)

interface WebRtcTransport {
    val incomingData: Flow<WebRtcIncomingDataFrame>
    val incomingSessionRequests: Flow<WebRtcIncomingSessionRequest>
    val sessionStates: Flow<WebRtcSessionState>

    suspend fun start(localPeer: PeerDescriptor)

    suspend fun stop()

    suspend fun initiateSession(target: PeerId): String

    suspend fun acceptSession(sessionId: String)

    suspend fun rejectSession(sessionId: String, reason: String)

    suspend fun sendData(sessionId: String, target: PeerId, payload: ByteArray)

    suspend fun closeSession(sessionId: String)
}

