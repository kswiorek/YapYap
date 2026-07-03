package org.yapyap.transport

import org.yapyap.protocol.PeerId

sealed class TransportException(message: String) : Exception(message) {
    sealed class WebRtcException(message: String) : TransportException(message) {
        class WrongTargetException(peerId: PeerId) : WebRtcException("Wrong target peerId: $peerId")
        class SessionNotFound(sessionId: String) : WebRtcException("Session not found: $sessionId")
        class DecodeError(message: String) : WebRtcException(message)
    }

    sealed class TorException(message: String) : TransportException(message) {
        class SocksError(message: String) : TorException(message)
        class SocksConnectionTimeout : TorException("Socks timeout")
        class TorRuntimeError(message: String) : TorException(message)
        class TransportFrameError(message: String) : TorException("Failed to parse transport frame: $message")
    }
}