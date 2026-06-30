package org.yapyap.transport.webrtc.types

import org.yapyap.protocol.PeerId

sealed class WebRtcException(message: String) : Exception(message) {
    class WrongTargetException(peerId: PeerId) : WebRtcException("Wrong target peerId: $peerId")
    class SessionNotFound(sessionId: String) : WebRtcException("Session not found: $sessionId")
    class DecodeError(message: String) : WebRtcException(message)
}