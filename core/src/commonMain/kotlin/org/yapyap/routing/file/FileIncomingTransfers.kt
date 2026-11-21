package org.yapyap.routing.file

import org.yapyap.protocol.FileId
import org.yapyap.protocol.TransferId
import org.yapyap.routing.store.FileIncomingTransfer
import org.yapyap.routing.store.FileIncomingTransferStore
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Owner of `file_incoming_transfers`: the target side's per-transfer rows (keys included).
 * The only holder of [FileIncomingTransferStore]: the carrier answers ended receives through
 * [endAllFor] instead of holding the store itself.
 */
internal class FileIncomingTransfers(
    private val store: FileIncomingTransferStore,
    private val clock: Clock,
) {
    suspend fun find(transferId: TransferId): FileIncomingTransfer? = store.find(transferId)

    /** OFFER accepted: the target side's row for this transfer. */
    suspend fun accept(row: FileIncomingTransfer) = store.insert(row)

    /** CANCEL from the author, or a refused chunk: drop the row. */
    suspend fun end(transferId: TransferId) = store.delete(transferId)

    /**
     * Receive ended: drops every incoming row of [fileId] and returns them so the caller can
     * answer each author once (COMPLETE when ready, CANCEL when it stopped).
     */
    /** Drops [fileId]'s rows and returns them, to answer their authors. */
    suspend fun endAllFor(fileId: FileId): List<FileIncomingTransfer> {
        val rows = store.findByFile(fileId)
        if (rows.isNotEmpty()) store.deleteByFile(fileId)
        return rows
    }

    /** Drops rows past their TTL; returns them for logging. */
    suspend fun expire(now: Instant = clock.now()): List<FileIncomingTransfer> = store.pruneExpired(now)
}
