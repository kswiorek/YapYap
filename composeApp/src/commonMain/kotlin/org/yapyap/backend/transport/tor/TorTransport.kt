package org.yapyap.backend.transport.tor

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.TorEndpoint

interface TorTransport {
    val incoming: Flow<BinaryEnvelope>

    suspend fun start(): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope)
}

