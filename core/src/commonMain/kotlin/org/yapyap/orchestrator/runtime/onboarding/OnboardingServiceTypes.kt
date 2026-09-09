package org.yapyap.orchestrator.runtime.onboarding

/** Outcome of the GUI-facing sponsor flow. Domain decisions are values; infrastructure
 *  failures (transport, storage) still throw. */
sealed interface SponsorOutcome {
    /** Append + intro done. [adminGranted] is false when the toggle was set on an
     *  existing-account invite, where it is meaningless — the device was still added;
     *  the GUI may surface a quiet note. */
    data class Sponsored(val adminGranted: Boolean) : SponsorOutcome

    /** Refused before any write — nothing appended, no intro sent. */
    data class Refused(val reason: SponsorRefusal) : SponsorOutcome
}

sealed interface SponsorRefusal {
    data object SponsorNotAdmin : SponsorRefusal   // incl. the revocation race
    data object SponsorNotReady : SponsorRefusal   // GLOBAL empty — still onboarding/syncing
    data class MalformedInvite(val defect: InviteDefect) : SponsorRefusal
}

enum class InviteDefect { MISSING_ACCOUNT_KEY, ACCOUNT_ID_MISMATCH }