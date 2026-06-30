package org.yapyap.transport.tor.transport

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.TorEndpoint

interface TorTransport {
    val incoming: Flow<TorIncomingEnvelope>

    suspend fun start(): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope)
}


data class TorIncomingEnvelope(
    val source: TorEndpoint,
    val envelope: BinaryEnvelope,
)