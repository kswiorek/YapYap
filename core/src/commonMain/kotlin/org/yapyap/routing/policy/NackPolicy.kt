package org.yapyap.routing.policy

import org.yapyap.protocol.envelopes.PacketNackReason

/**
 * What a received NACK means for the outbox row.
 *
 * - [REMOVE]: the verdict is final for these bytes — delete the row.
 * - [KEEP]: the context may heal (endpoint reconciliation) — keep the row on its
 *   existing retry schedule. The fast-attempt budget self-limits the cadence.
 */
internal enum class NackAction {
    REMOVE,
    KEEP,
}

/**
 * Single decision point mapping a received NACK reason to the outbox action.
 * Exhaustive on purpose: a new [PacketNackReason] must decide here at compile time.
 */
internal fun nackActionFor(reason: PacketNackReason?): NackAction =
    when (reason) {
        // Unrecognized reason byte from a newer peer — never act destructively on confusion.
        null -> NackAction.KEEP

        PacketNackReason.PERMANENT_PROTECTION_FAILED,
        PacketNackReason.DECODE_FAILED,
            // Version skew — the peer pulls the message via sync after updating.
        PacketNackReason.UNSUPPORTED_TYPE,
        PacketNackReason.EXPIRED,
        PacketNackReason.DECLINED,
            -> NackAction.REMOVE

        PacketNackReason.WRONG_TARGET -> NackAction.KEEP
    }
