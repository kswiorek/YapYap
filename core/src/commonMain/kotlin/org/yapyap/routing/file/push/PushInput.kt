package org.yapyap.routing.file.push

import org.yapyap.protocol.envelopes.ChunkRange
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.routing.router.OutboxOutcome

/**
 * The actor's mailbox: every signal a transfer cares about, serialised through one channel
 * so per-transfer state has a single writer.
 */
internal sealed interface PushInput {
    data class Outcome(val outcome: OutboxOutcome) : PushInput
    data class CoverageAck(val highestContiguousIndex: Int, val missing: List<ChunkRange>) : PushInput
    data class Cancelled(val reason: FileCancelReason) : PushInput
    data object UserCancel : PushInput
    data object SilentCancel : PushInput // supersede: CANCEL sent, row ends, no event
    data object LinkClosed : PushInput
    data object Tick : PushInput
}
