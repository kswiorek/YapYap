package org.yapyap.orchestrator.runtime.globalevent

/**
 * Shared outcome of GUI-facing global-event publishes (admin mutations and
 * self-service alike). Domain refusals are values; infrastructure failures
 * (transport, storage) still throw.
 */
sealed interface GlobalEventOutcome {
    /** Appended to GLOBAL + broadcast; local fold already committed. */
    data object Published : GlobalEventOutcome

    /** Refused before any write — nothing appended, nothing broadcast. */
    data class Refused(val reason: GlobalEventRefusal) : GlobalEventOutcome
}

sealed interface GlobalEventRefusal {
    /** Local account lacks the admin flag (incl. the revocation race). */
    data object NotAdmin : GlobalEventRefusal

    /** GLOBAL frontier unchainable — still syncing. */
    data object NotReady : GlobalEventRefusal

    /** No such account/device row. */
    data object TargetNotFound : GlobalEventRefusal

    /** Self-service target belongs to another account. */
    data object NotOwnDevice : GlobalEventRefusal
}
