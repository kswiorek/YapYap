package org.yapyap.routing.file.fetch

import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.FileFetchAbortReason

/** Outcome of one requester fetch (Step 22 maps these onto the request loop). */
internal sealed interface FileFetchOutcome {
    data object Satisfied : FileFetchOutcome
    data class Declined(val reason: String) : FileFetchOutcome
    data class Failed(val reason: String) : FileFetchOutcome
    /** Channel or session died first: the request stays PENDING and retries. */
    data object Interrupted : FileFetchOutcome
}

/** Why a holder refused to serve (maps to FETCH_DECLINED reasons). */
internal enum class FetchDeclineCause {
    NOT_HELD,
    NOT_ALLOWED,
}
