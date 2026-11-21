package org.yapyap.routing.file.fetch

/**
 * Outcome of one requester fetch, for the candidate core (design §6.6, §9).
 * Enums, not strings: every outcome maps to a [CallOutcome] in `FileFetchStrategy`.
 */
internal enum class FetchResult {
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
