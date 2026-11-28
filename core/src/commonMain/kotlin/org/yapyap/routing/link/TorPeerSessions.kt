package org.yapyap.routing.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.router.DeviceRouteStore
import org.yapyap.transport.session.SessionStatus
import org.yapyap.transport.session.SessionTransport
import org.yapyap.transport.tor.TorLinkId
import org.yapyap.transport.tor.transport.TorTransport

/**
 * [TorTransport]'s sessions re-keyed by device id. Tor addresses onion endpoints, and which
 * device owns an endpoint is routing knowledge, so the mapping lives here and the transport
 * stays endpoint-keyed: inbound streams claim an endpoint, and bootstrap targets have nothing
 * else.
 *
 * Every Tor session the router makes to a known device binds its endpoint on the way — an open
 * ([openSession]), a send ([resolve]), an adopted inbound stream ([adopt]) — so a live session to
 * a known device is always visible under its id. [DeviceRouteStore] stays the source of truth;
 * the bindings mirror it plus what authenticated traffic proved ([bind]).
 */
internal class TorPeerSessions(
    private val routes: DeviceRouteStore,
    private val torTransport: TorTransport,
) : SessionTransport<PeerId> {

    /** Tor endpoint per peer as last resolved or proven. */
    private val endpoints = MutableStateFlow<Map<PeerId, TorEndpoint>>(emptyMap())

    override val sessionStates: Flow<Map<PeerId, SessionStatus>> =
        combine(torTransport.sessionStates, endpoints) { states, bound ->
            buildMap { bound.forEach { (peer, endpoint) -> states[endpoint]?.let { put(peer, it) } } }
        }

    override fun hasSession(key: PeerId): Boolean =
        endpoints.value[key]?.let { torTransport.hasSession(it) } ?: false

    override suspend fun openSession(key: PeerId) {
        val endpoint = resolve(key) ?: error("No Tor endpoint known for $key")
        torTransport.openSession(endpoint)
    }

    override suspend fun closeSession(key: PeerId) {
        endpoints.value[key]?.let { torTransport.closeSession(it) }
    }

    /** Records [peer]'s current Tor endpoint (resolved, or proven by authenticated traffic). */
    fun bind(peer: PeerId, endpoint: TorEndpoint) {
        if (endpoints.value[peer] != endpoint) endpoints.update { it + (peer to endpoint) }
    }

    /** [peer]'s Tor endpoint as the router knows it now (bound on the way); null when unknown. */
    suspend fun resolve(peer: PeerId): TorEndpoint? {
        val endpoint = routes.resolveTorEndpointForDevice(peer) ?: return null
        bind(peer, endpoint)
        return endpoint
    }

    /**
     * Makes the inbound Tor stream [link] the Tor link to [peer], whose packets on it were just
     * authenticated. Only call for traffic the peer sends directly (never relayed), so the
     * stream's dialer is known to be [peer].
     */
    fun adopt(peer: PeerId, link: TorLinkId, endpoint: TorEndpoint): Boolean {
        bind(peer, endpoint)
        return torTransport.adoptInbound(link, endpoint)
    }
}
