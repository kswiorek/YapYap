package org.yapyap.routing.link

import kotlinx.coroutines.flow.*
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.router.RouterTransport
import org.yapyap.transport.session.SessionStatus
import org.yapyap.transport.session.SessionTransport
import org.yapyap.transport.session.connectedKeys
import org.yapyap.transport.tor.TorLinkId
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport

/** A link to [peer] over [transport] became usable. */
internal data class PeerLinkUp(val peer: PeerId, val transport: RouterTransport)

/**
 * The router's view of live sessions ("links") to peers, across every session transport and
 * keyed by device id. Transports key their sessions by whatever they address (WebRTC: device
 * id; Tor: onion endpoint); this is the one place that maps between the two, so everything
 * above it — transport choice, outbox parking, keeping links open — works per peer and per
 * [RouterTransport] without knowing transport specifics.
 */
internal class PeerLinks(
    private val identityResolver: IdentityResolver,
    private val webRtcTransport: WebRtcTransport,
    private val torTransport: TorTransport,
) {
    /** Tor endpoint per peer as last resolved (or healed from authenticated traffic). */
    private val torEndpoints = MutableStateFlow<Map<PeerId, TorEndpoint>>(emptyMap())

    /** Tor sessions seen per device id. */
    val tor: SessionTransport<PeerId> = TorPeerSessions()

    fun sessions(transport: RouterTransport): SessionTransport<PeerId> = when (transport) {
        RouterTransport.WEBRTC -> webRtcTransport
        RouterTransport.TOR -> tor
    }

    /** Transports with a usable link to [peer] right now, from what is already known (no lookups). */
    fun currentLinks(peer: PeerId): Set<RouterTransport> =
        RouterTransport.entries.filterTo(mutableSetOf()) { sessions(it).hasSession(peer) }

    /** Like [currentLinks], first resolving [peer]'s Tor endpoint when it isn't known yet. */
    suspend fun linksOf(peer: PeerId): Set<RouterTransport> {
        if (peer !in torEndpoints.value) resolveTorEndpoint(peer)
        return currentLinks(peer)
    }

    /** Every link that becomes usable, on any transport (one emission per transition). */
    val linkUps: Flow<PeerLinkUp> = RouterTransport.entries
        .map { transport -> sessions(transport).sessionStates.connectedKeys().map { PeerLinkUp(it, transport) } }
        .merge()

    /** Records [peer]'s current Tor endpoint (resolved, or healed from authenticated traffic). */
    fun bindTorEndpoint(peer: PeerId, endpoint: TorEndpoint) {
        if (torEndpoints.value[peer] != endpoint) torEndpoints.update { it + (peer to endpoint) }
    }

    /**
     * Makes the inbound Tor stream [link] the Tor link to [peer], whose packets on it were just
     * authenticated. Only call for traffic the peer sends directly (never relayed), so the
     * stream's dialer is known to be [peer].
     */
    fun adoptTorLink(peer: PeerId, link: TorLinkId, endpoint: TorEndpoint): Boolean {
        bindTorEndpoint(peer, endpoint)
        return torTransport.adoptInbound(link, endpoint)
    }

    suspend fun resolveTorEndpoint(peer: PeerId): TorEndpoint? {
        val endpoint = try {
            identityResolver.resolveTorEndpointForDevice(peer)
        } catch (_: CryptoException) {
            null
        } ?: return null
        bindTorEndpoint(peer, endpoint)
        return endpoint
    }

    /** [TorTransport]'s endpoint-keyed sessions, re-keyed by the peers bound to those endpoints. */
    private inner class TorPeerSessions : SessionTransport<PeerId> {
        override val sessionStates: Flow<Map<PeerId, SessionStatus>> =
            combine(torTransport.sessionStates, torEndpoints) { states, endpoints ->
                buildMap { endpoints.forEach { (peer, endpoint) -> states[endpoint]?.let { put(peer, it) } } }
            }

        override fun hasSession(key: PeerId): Boolean =
            torEndpoints.value[key]?.let { torTransport.hasSession(it) } ?: false

        override suspend fun openSession(key: PeerId) {
            val endpoint = resolveTorEndpoint(key) ?: error("No Tor endpoint known for $key")
            torTransport.openSession(endpoint)
        }

        override suspend fun closeSession(key: PeerId) {
            torEndpoints.value[key]?.let { torTransport.closeSession(it) }
        }
    }
}
