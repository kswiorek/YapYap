package org.yapyap.routing.file.inbound

import org.yapyap.protocol.TransferId
import org.yapyap.routing.file.IncomingHop
import org.yapyap.routing.router.InboundHandleResult

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
