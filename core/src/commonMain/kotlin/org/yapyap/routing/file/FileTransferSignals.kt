package org.yapyap.routing.file

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.ChunkRange
import org.yapyap.protocol.envelopes.FileCancelReason

/**
 * Signals from the inbound target path into the push engine (design §3.5–§3.6).
 * The `from` parameter is the transfer row's authorisation check: ACK, COMPLETE and
 * CANCEL act only when the envelope source is the row's target device (or its relay).
 */
internal interface FileTransferSignals {
    suspend fun coverageAck(
        transferId: TransferId,
        from: PeerId,
        highestContiguousIndex: Int,
        missing: List<ChunkRange>,
    )

    suspend fun complete(transferId: TransferId, from: PeerId)
    suspend fun cancelled(transferId: TransferId, from: PeerId, reason: FileCancelReason)
}
