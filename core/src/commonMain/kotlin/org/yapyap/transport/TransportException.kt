package org.yapyap.transport

import org.yapyap.protocol.PeerId

internal sealed class TransportException(message: String) : Exception(message) {
    /** Payload exceeds the backend's configured max; fail fast instead of losing it on the wire. */
    class PayloadTooLarge(val sizeBytes: Int, val limitBytes: Int) : TransportException(
        "Payload $sizeBytes exceeds limit $limitBytes",
    )

    /** Loopback-only failure (unreachable peer, non-loopback target). Never thrown by real backends. */
    class LoopbackError(message: String) : TransportException("Loopback transport failed: $message")

    sealed class WebRtcException(message: String) : TransportException(message) {
        class WrongTargetException(peerId: PeerId) : WebRtcException("Wrong target peerId: $peerId")
        class DecodeError(message: String) : WebRtcException(message)
    }

    sealed class TorException(message: String) : TransportException(message) {
        class SocksError(message: String) : TorException(message)

        /**
         * Tor found no descriptor for [onionAddress] (SOCKS code 4): the onion isn't
         * published — or Tor still remembers an earlier miss after it was.
         */
        class OnionNotFound(val onionAddress: String) : TorException("Onion descriptor not found: $onionAddress")
        class SocksConnectionTimeout : TorException("Socks timeout")
        class TorRuntimeError(message: String) : TorException(message)
        class TransportFrameError(message: String) : TorException("Failed to parse transport frame: $message")
    }
}