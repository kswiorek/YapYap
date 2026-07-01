package org.yapyap.transport.tor.transport

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.tor.TorIncomingEnvelope

interface TorTransport {
    val incoming: Flow<TorIncomingEnvelope>

    suspend fun start(): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope)
}