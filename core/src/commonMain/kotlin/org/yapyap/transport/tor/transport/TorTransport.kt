package org.yapyap.transport.tor.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.tor.backend.TorPublishState

internal interface TorTransport {
    val incoming: Flow<TorIncomingEnvelope>

    /** Mirrors the backend's onion publish readiness (loopback: published on start). */
    val publishState: StateFlow<TorPublishState>

    suspend fun start(): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope)
}