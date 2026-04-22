package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId

/**
 * JVM WebRTC backend skeleton.
 *
 * This is a stub for webrtc-java integration and intentionally keeps signaling/data
 * method bodies unimplemented until the full implementation pass.
 */
class JvmWebRtcBackend : WebRtcBackend {

    private val outgoingSignalFlow = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val incomingDataFlow = MutableSharedFlow<WebRtcIncomingDataFrame>(extraBufferCapacity = 64)
    private val sessionEventFlow = MutableSharedFlow<WebRtcSessionEvent>(extraBufferCapacity = 64)

    override val outgoingSignals: Flow<WebRtcSignal> = outgoingSignalFlow.asSharedFlow()
    override val incomingDataFrames: Flow<WebRtcIncomingDataFrame> = incomingDataFlow.asSharedFlow()
    override val sessionEvents: Flow<WebRtcSessionEvent> = sessionEventFlow.asSharedFlow()

    private var localPeer: PeerDescriptor? = null

    override suspend fun start(localPeer: PeerDescriptor) {
        check(this.localPeer == null) { "WebRTC backend is already started" }
        this.localPeer = localPeer
    }

    override suspend fun stop() {
        localPeer = null
    }

    override suspend fun openSession(target: PeerId, sessionId: String) {
        check(localPeer != null) { "WebRTC backend must be started before opening session" }
        TODO("Implement webrtc-java offer creation and session bootstrap")
    }

    override suspend fun handleRemoteSignal(signal: WebRtcSignal) {
        check(localPeer != null) { "WebRTC backend must be started before applying remote signal" }
        TODO("Implement webrtc-java remote signaling handling")
    }

    override suspend fun closeSession(sessionId: String) {
        check(localPeer != null) { "WebRTC backend must be started before closing session" }
        TODO("Implement webrtc-java peer connection/session disposal")
    }

    override suspend fun sendData(sessionId: String, target: PeerId, payload: ByteArray) {
        check(localPeer != null) { "WebRTC backend must be started before sending data" }
        TODO("Implement webrtc-java data channel send")
    }
}

