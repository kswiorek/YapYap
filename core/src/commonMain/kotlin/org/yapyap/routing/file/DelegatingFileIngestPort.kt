package org.yapyap.routing.file

import org.yapyap.protocol.FileId

/**
 * Late-bound ingest port: the router is built before the receive engine exists in the
 * composition order, so the handler holds this and wiring swaps in the engine before start.
 */
internal class DelegatingFileIngestPort : FileIngestPort {
    lateinit var delegate: FileIngestPort

    override suspend fun checkOffer(offer: FileOfferInfo): FileOfferVerdict =
        delegate.checkOffer(offer)

    override suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray): FileIngestResult =
        delegate.ingest(fileId, byteOffset, plaintext)
}
