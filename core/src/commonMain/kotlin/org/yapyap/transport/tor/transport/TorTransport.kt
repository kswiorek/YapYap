package org.yapyap.transport.tor.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import org.yapyap.transport.tor.backend.TorRecoveryStep

internal interface TorTransport {
    val incoming: Flow<TorIncomingEnvelope>

    /** Mirrors the backend's onion publish readiness (loopback: published on start). */
    val publishState: StateFlow<TorPublishState>

    /** Mirrors the backend's daemon health (loopback: ready on start). */
    val health: StateFlow<TorHealth>

    /** Mirrors the backend's current recovery step (loopback: never recovering). */
    val recovery: StateFlow<TorRecoveryStep?>

    suspend fun start(): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope)

    /**
     * Drops Tor's cached onion lookups so a peer that was "not found" earlier is
     * looked up afresh. Global and throttled; true only when it refreshed now.
     */
    suspend fun refreshOnionLookups(): Boolean
}