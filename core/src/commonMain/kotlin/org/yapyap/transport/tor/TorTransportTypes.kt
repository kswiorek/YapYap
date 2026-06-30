package org.yapyap.transport.tor

import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope

data class TorIncomingFrame(
    val source: TorEndpoint,
    val payload: ByteArray,
)

data class TorIncomingEnvelope(
    val source: TorEndpoint,
    val envelope: BinaryEnvelope,
)

sealed class TorException(message: String) : Exception(message) {
    class SocksError(message: String) : TorException(message)
    class SocksConnectionTimeout : TorException("Socks timeout")
    class TorRuntimeError(message: String) : TorException(message)
    class TransportFrameError(message: String) : TorException("Failed to parse transport frame: $message")
}