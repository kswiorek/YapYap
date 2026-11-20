package org.yapyap.routing.file

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.routing.router.Router

/**
 * Late-bound ingest port: the router is built before the receive machinery exists in the
 * composition order, so the handler holds this and wiring swaps in the coordinator before start.
 *
 * Scheduled debt (guide §3): the router↔coordinator construction cycle (the router needs
 * the port; the port's engine needs the router's carrier) forces this post-construction
 * patching, against the guide §5 rule. It goes away with the router-service split (the
 * TODO on [Router.startFileRequest]): per-task file services are built before the router
 * from stores and config, so the port implementation is constructed first and passed in.
 */
internal class DelegatingFileIngestPort : FileIngestPort {
    lateinit var delegate: FileIngestPort

    override suspend fun checkOffer(offer: FileOfferInfo): FileOfferVerdict =
        delegate.checkOffer(offer)

    override suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray): FileIngestResult =
        delegate.ingest(fileId, byteOffset, plaintext)

    override suspend fun heldCoverage(fileId: FileId): FileCoverage? =
        delegate.heldCoverage(fileId)

    override suspend fun requestCandidates(fileId: FileId): List<AccountId> =
        delegate.requestCandidates(fileId)

    override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean =
        delegate.canServe(fileId, requester)

    override suspend fun requestTotalBytes(fileId: FileId): Long? =
        delegate.requestTotalBytes(fileId)
}
