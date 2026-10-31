package org.yapyap.transport.tor.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.session.SessionTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.tor.TorLinkId
import org.yapyap.transport.tor.TorSessionState
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import org.yapyap.transport.tor.backend.TorRecoveryStep
import kotlin.time.Duration

/**
 * Packet-level Tor transport keyed by onion endpoint (it knows nothing about devices).
 *
 * Two ways to reach an endpoint:
 * - **Session** ([SessionTransport]): a long-lived stream carrying any number of envelopes
 *   both ways. A session we dialed is CONNECTED once the far end's app accepts it. A stream
 *   someone else dialed is only a session after [adoptInbound]: its claimed source endpoint is
 *   unauthenticated, so the layer that can verify who sent its packets decides.
 * - **One-shot** ([send] without a session): one dial per envelope, as for a bootstrap
 *   target or a peer with no session.
 */
internal interface TorTransport : SessionTransport<TorEndpoint> {
    val incoming: Flow<TorIncomingEnvelope>

    override val sessionStates: StateFlow<Map<TorEndpoint, TorSessionState>>

    /** Mirrors the backend's onion publish readiness (loopback: published on start). */
    val publishState: StateFlow<TorPublishState>

    /** Mirrors the backend's daemon health (loopback: ready on start). */
    val health: StateFlow<TorHealth>

    /** Mirrors the backend's current recovery step (loopback: never recovering). */
    val recovery: StateFlow<TorRecoveryStep?>

    suspend fun start(): TorEndpoint

    suspend fun stop()

    /**
     * Sends over the first usable of: the stream [via] (a reply on the stream a packet
     * arrived on), a session to [target], a one-shot dial. [budget] caps how long a one-shot
     * dial may keep retrying (null: the backend's configured budget).
     */
    suspend fun send(
        target: TorEndpoint,
        envelope: BinaryEnvelope,
        budget: Duration? = null,
        via: TorLinkId? = null,
    )

    /**
     * Makes the inbound stream [link] the session to [endpoint], once the caller has
     * authenticated its sender as [endpoint]'s owner. False when the stream is gone or was
     * opened claiming a different endpoint.
     */
    fun adoptInbound(link: TorLinkId, endpoint: TorEndpoint): Boolean

    /**
     * Drops Tor's cached onion lookups so a peer that was "not found" earlier is
     * looked up afresh. Global and throttled; true only when it refreshed now.
     */
    suspend fun refreshOnionLookups(): Boolean
}