package org.yapyap.routing.file.inbound

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.TransferShape
import org.yapyap.routing.router.InboundHandleResult

/** A transfer delivering to us: who sends it and what it describes. */
internal class IncomingHop(val transferId: TransferId, val sender: PeerId, val shape: TransferShape)

/**
 * Where an incoming transfer's chunks go (docs/envelope-redesign.md §5.1): the target decrypts
 * and ingests them, a relay parks the ciphertext. The inbound handler checks every chunk against
 * its transfer (sender, index range, exact length) before a sink sees it.
 */
internal interface ChunkSink {
    /** The live transfer [transferId] if this sink receives it. */
    suspend fun find(transferId: TransferId): IncomingHop?

    /** One checked chunk: handled, and the coverage ACK queued as a side effect. */
    suspend fun accept(hop: IncomingHop, index: Int, ciphertext: ByteArray): InboundHandleResult
}
