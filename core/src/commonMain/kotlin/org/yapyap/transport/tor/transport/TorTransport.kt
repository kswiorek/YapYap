package org.yapyap.transport.tor.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import org.yapyap.transport.tor.backend.TorRecoveryStep
import kotlin.time.Duration

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

    /** [budget] caps how long the send may keep retrying (null: the backend's configured budget). */
    suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope, budget: Duration? = null)

    /**
     * Drops Tor's cached onion lookups so a peer that was "not found" earlier is
     * looked up afresh. Global and throttled; true only when it refreshed now.
     */
    suspend fun refreshOnionLookups(): Boolean
}