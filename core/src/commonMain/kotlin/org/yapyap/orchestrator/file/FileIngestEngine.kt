package org.yapyap.orchestrator.file

import org.yapyap.protocol.FileId
import org.yapyap.routing.file.FileCoverage
import org.yapyap.routing.file.FileIngestPort
import org.yapyap.routing.file.FileIngestResult
import org.yapyap.routing.file.FileOfferInfo
import org.yapyap.routing.file.FileOfferVerdict

/**
 * Ingest side of the routing-declared [FileIngestPort]: the receive engine's
 * synchronous handoff (design §8 — "ACK = decrypted and durably recorded").
 *
 * Narrower than the port so port implementors depend only on the bytes, never
 * on the engine itself (guide §5: constructor-injected interfaces).
 */
internal interface FileIngestEngine {
    suspend fun checkOffer(offer: FileOfferInfo): FileOfferVerdict
    suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray): FileIngestResult
    suspend fun heldCoverage(fileId: FileId): FileCoverage?
}
