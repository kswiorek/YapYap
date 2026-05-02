package org.yapyap.backend.transport.tor

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

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