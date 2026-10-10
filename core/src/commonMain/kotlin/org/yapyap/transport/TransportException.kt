package org.yapyap.transport

import org.yapyap.protocol.PeerId

internal sealed class TransportException(message: String) : Exception(message) {
    /** Payload exceeds the backend's configured max; fail fast instead of losing it on the wire. */
    class PayloadTooLarge(val sizeBytes: Int, val limitBytes: Int) : TransportException(
        "Payload $sizeBytes exceeds limit $limitBytes",
    )

    sealed class WebRtcException(message: String) : TransportException(message) {
        class WrongTargetException(peerId: PeerId) : WebRtcException("Wrong target peerId: $peerId")
        class DecodeError(message: String) : WebRtcException(message)
    }

    sealed class TorException(message: String) : TransportException(message) {
        class SocksError(message: String) : TorException(message)
        class SocksConnectionTimeout : TorException("Socks timeout")
        class TorRuntimeError(message: String) : TorException(message)
        class TransportFrameError(message: String) : TorException("Failed to parse transport frame: $message")
    }
}