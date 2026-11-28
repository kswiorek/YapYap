package org.yapyap.routing.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.RouterTransport
import org.yapyap.transport.session.SessionTransport
import org.yapyap.transport.session.connectedKeys
import org.yapyap.transport.session.disconnectedKeys

/** A link to [peer] over [transport] became usable. */
internal data class PeerLinkUp(val peer: PeerId, val transport: RouterTransport)

/**
 * The router's view of live sessions ("links") to peers, across every session transport and
 * keyed by device id. Each transport's sessions come already keyed by device id (Tor through
 * [TorPeerSessions]), so everything above — transport choice, outbox parking, keeping links
 * open — works per peer and per [RouterTransport] without knowing transport specifics.
 */
internal class PeerLinks(
    private val transports: Map<RouterTransport, SessionTransport<PeerId>>,
) {
    init {
        require(transports.keys == RouterTransport.entries.toSet()) { "PeerLinks needs every transport's sessions" }
    }

    fun sessions(transport: RouterTransport): SessionTransport<PeerId> = transports.getValue(transport)

    /** Transports with a usable link to [peer] right now. */
    fun linksOf(peer: PeerId): Set<RouterTransport> =
        transports.filterTo(mutableMapOf()) { (_, sessions) -> sessions.hasSession(peer) }.keys

    /** Every link becoming usable, on any transport (one emission per transition). */
    val linkUps: Flow<PeerLinkUp> = transports
        .map { (transport, sessions) -> sessions.sessionStates.connectedKeys().map { PeerLinkUp(it, transport) } }
        .merge()

    /** Every link ceasing to be usable, on any transport (deposit-on-close, §6.3). */
    val linkDowns: Flow<PeerId> = transports.values
        .map { it.sessionStates.disconnectedKeys() }
        .merge()
}
