package org.yapyap.routing.file.inbound

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.store.IncomingTransfer

/**
 * What the receiving end of a transfer does with it (docs/envelope-redesign.md §5.1): the target
 * decrypts and ingests, a relay parks the ciphertext and forwards it once whole. The transfer is
 * the same either way: [FileInboundHandler] picks the sink by the seal's final target and runs
 * the checks every hop passes before a sink sees it.
 */
internal interface TransferSink {
    /**
     * A new hop's OFFER, past the shared admission (a new transfer, an acceptable shape within
     * our small-file limit, a seal that fits). Admitting it records the [IncomingTransfer].
     */
    suspend fun offer(env: BinaryEnvelope, header: FileHeader.Offer): InboundHandleResult

    /** One checked chunk of [transfer]: handled, and the coverage ACK queued as a side effect. */
    suspend fun accept(transfer: IncomingTransfer, index: Int, ciphertext: ByteArray): InboundHandleResult

    /** [transfer]'s sender ended it. */
    suspend fun cancel(transfer: IncomingTransfer)
}
