package org.yapyap.routing.file

import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.routing.router.RoutingContext
import kotlin.time.Instant

/**
 * A header-only FILE packet (ACK, CANCEL, COMPLETE) from us to [peer]. Control packets are
 * politeness: ordinary routable packets, never relay-backed, bounded by [expiresAt].
 */
//TODO: no extensions for types we control, seems the context was hijacked to pass the file control function around
internal suspend fun RoutingContext.fileControl(peer: PeerId, header: FileHeader, expiresAt: Instant): BinaryEnvelope =
    envelope(
        packetType = PacketType.FILE,
        target = peer,
        routingHeader = header.encode(),
        expiresAt = expiresAt,
        dispositionRequested = true,
    )
