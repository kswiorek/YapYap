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