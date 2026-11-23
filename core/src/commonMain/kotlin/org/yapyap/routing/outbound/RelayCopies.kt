package org.yapyap.routing.outbound

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.store.OutboxTag

/**
 * Makes the relay copies of one due outbox row; returns how many. The outbox looks the sink up
 * by the row's tag kind — the tag already names who waits on the outcome, so no packet-type
 * sniffing and no id re-resolution.
 */
internal fun interface RelayCopies {
    suspend fun deposit(envelope: BinaryEnvelope, tag: OutboxTag): Int
}
