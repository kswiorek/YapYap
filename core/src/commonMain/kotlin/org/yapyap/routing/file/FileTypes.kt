package org.yapyap.routing.file

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.TransferShape
import org.yapyap.routing.store.OutgoingTransfer

/** What happened to an outgoing transfer; its owner (the seal registry or the relay forwards) reacts. */
internal sealed interface TransferEvent {
    val transfer: OutgoingTransfer

    /** The peer's coverage moved. */
    data class Progress(override val transfer: OutgoingTransfer) : TransferEvent

    /** The peer looks gone (no link, no ACK in time, or its link closed): a deposit trigger. */
    data class Gone(override val transfer: OutgoingTransfer) : TransferEvent

    /** The transfer ended; [OutgoingTransfer.status] says how. */
    data class Ended(override val transfer: OutgoingTransfer) : TransferEvent
}

/** What an outgoing transfer's OFFER carries: the seal's origin, final target and the seal bytes. */
internal class TransferOffer(val origin: PeerId, val finalTarget: PeerId, val sealBytes: ByteArray)

/** A transfer delivering to us: who sends it and what it describes. */
internal class IncomingHop(val transferId: TransferId, val sender: PeerId, val shape: TransferShape)

/**
 * Outcome of one large-file pull, for the candidate core (design §6.6, §9).
 * Enums, not strings: every outcome maps to a `CallOutcome` in `LargeFileStrategy`.
 */
internal enum class LargeFileResult {
    /** Bytes complete and READY (or already held): drop the request row. */
    SATISFIED,

    /** The holder declined (not held, not allowed, or a bad block list): ask the next one. */
    DECLINED,

    /** No answer, a drop, a holder ABORT, or a failure on our side: backoff, usual candidate order. */
    UNANSWERED,

    /** Online but no WebRTC session could be made: PENDING, never Tor (design §6.6). */
    NO_DIRECT_CONNECTION,

    /** Receiver out of space: the engine declined the receive; drop the row, no blacklist. */
    NO_SPACE,

    /** A block failed its hash on our side: ABORT, PENDING, usual candidate order. */
    MISMATCH,

    /** The local receive ended (declined, failed, forgotten): drop the row, no blacklist. */
    ENDED,
}
