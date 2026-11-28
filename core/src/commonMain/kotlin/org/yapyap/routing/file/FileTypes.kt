package org.yapyap.routing.file

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileDeclineReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.store.OutgoingTransfer
import org.yapyap.transport.webrtc.types.FileChannelId
import kotlin.concurrent.Volatile
import kotlin.time.Instant

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

/** What one PULL's channel claim found. */
internal enum class LargeFileServeClaim {
    /** The channel is now ours to gate and serve. */
    CLAIMED,

    /** A PULL on this channel is already being handled: ignore the repeat. */
    ALREADY,

    /** The peer already holds the maximum serves: tell it to come back later. */
    BUSY,
}

/** What the large-file serve gate decided for one PULL. */
internal sealed interface LargeFileAdmission {
    /** Serve [manifest] to [account]'s device. */
    data class Allowed(val account: AccountId, val manifest: FileManifest) : LargeFileAdmission

    /** I don't hold it, or I won't serve it: answer DECLINED and close. */
    data class Declined(val reason: LargeFileDeclineReason) : LargeFileAdmission

    /** The request is structurally unusable (a bad offset): ABORT and close. */
    data class Abort(val reason: LargeFileAbortReason, val text: String?) : LargeFileAdmission
}

/**
 * One large-file serve's live state (design §6.6): the last PROGRESS the requester reported and
 * when it last moved. The holder streams within the window past [lastProgress]; the mux updates
 * both from the requester's frames.
 */
internal class HolderSession(
    val channel: FileChannelId,
    @Volatile var lastProgress: Long,
    @Volatile var lastProgressAt: Instant,
)

/** What the mux hands one requester session. */
internal sealed interface RequesterInput {
    data class Frame(val frame: LargeFileFrame) : RequesterInput
    data object ChannelClosed : RequesterInput
}

/** The holder's answer to our PULL. */
internal sealed interface RequesterAnswer {
    data class Ok(val ok: LargeFileFrame.PullOk) : RequesterAnswer

    /** The holder's own DECLINED: the only answer that counts as a decline. */
    data class Declined(val reason: LargeFileDeclineReason) : RequesterAnswer

    /**
     * Settled on our side or by the holder giving up, never a decline by the holder: our own
     * refusal or Defer at PULL_OK, a holder ABORT. The pull returns [result] as it is.
     */
    data class Settled(val result: LargeFileResult) : RequesterAnswer

    /** The channel closed before any answer: backoff, never a decline. */
    data object Closed : RequesterAnswer
}
