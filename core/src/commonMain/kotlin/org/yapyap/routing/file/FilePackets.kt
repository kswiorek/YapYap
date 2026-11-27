package org.yapyap.routing.file

import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.OutgoingTransfer
import kotlin.time.Instant

/**
 * Every FILE envelope this device builds (design §3, docs/envelope-redesign.md §5): the one
 * place that knows how a FILE packet is addressed and framed, so a change there cannot miss a
 * builder. Constructed by the file service with the routing context.
 */
internal class FilePackets(private val ctx: RoutingContext) {
    /**
     * A header-only control packet (ACK, CANCEL, COMPLETE) from us to [peer]. Control packets are
     * politeness: ordinary routable packets, never relay-backed, bounded by [expiresAt].
     */
    suspend fun control(peer: PeerId, header: FileHeader, expiresAt: Instant): BinaryEnvelope =
        ctx.envelope(
            packetType = PacketType.FILE,
            target = peer,
            routingHeader = header.encode(),
            expiresAt = expiresAt,
            dispositionRequested = true,
        )

    /** A transfer's OFFER: the seal content with its public shape. */
    suspend fun offer(transfer: OutgoingTransfer, offer: TransferOffer): BinaryEnvelope =
        ctx.envelope(
            packetType = PacketType.FILE,
            target = transfer.peer,
            routingHeader = FileHeader.Offer(
                transfer.transferId,
                offer.origin,
                offer.finalTarget,
                transfer.shape,
            ).encode(),
            content = offer.sealBytes,
            expiresAt = transfer.expiresAt,
            dispositionRequested = true,
        )

    /** One CHUNK row of [transfer] at [index], carrying its [ciphertext]. */
    suspend fun chunk(transfer: OutgoingTransfer, index: Int, ciphertext: ByteArray): BinaryEnvelope =
        ctx.envelope(
            packetType = PacketType.FILE,
            target = transfer.peer,
            routingHeader = FileHeader.Chunk(transfer.transferId, index).encode(),
            content = ciphertext,
            expiresAt = transfer.expiresAt,
            dispositionRequested = true,
        )

    /** A sealed REQUEST to [device], carrying [sealedContent] and bounded by [expiresAt]. */
    suspend fun request(device: PeerId, sealedContent: ByteArray, expiresAt: Instant): BinaryEnvelope =
        ctx.envelope(
            packetType = PacketType.FILE,
            target = device,
            routingHeader = FileHeader.Request.encode(),
            content = sealedContent,
            expiresAt = expiresAt,
            dispositionRequested = true,
        )
}
