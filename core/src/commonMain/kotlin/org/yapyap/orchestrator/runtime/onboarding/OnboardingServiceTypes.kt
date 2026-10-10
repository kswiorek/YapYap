package org.yapyap.orchestrator.runtime.onboarding

/** Outcome of the GUI-facing sponsor flow. Domain decisions are values; infrastructure
 *  failures (transport, storage) still throw. */
public sealed interface SponsorOutcome {
    /** Append + intro done. [adminGranted] is false when the toggle was set on an
     *  existing-account invite, where it is meaningless — the device was still added;
     *  the GUI may surface a quiet note. */
    public data class Sponsored(val adminGranted: Boolean) : SponsorOutcome

    /** Refused before any write — nothing appended, no intro sent. */
    public data class Refused(val reason: SponsorRefusal) : SponsorOutcome
}

public sealed interface SponsorRefusal {
    public data object SponsorNotAdmin : SponsorRefusal   // incl. the revocation race
    public data object SponsorNotReady :
        SponsorRefusal   // GLOBAL empty or unchainable (every tip parked) — still onboarding/syncing

    public data class MalformedInvite(val defect: InviteDefect) : SponsorRefusal
}

public enum class InviteDefect {
    MISSING_ACCOUNT_KEY,
    ACCOUNT_ID_MISMATCH,

    /** The scanned bytes do not decode as an invite at all (corrupt QR / version skew). */
    MALFORMED_BYTES,
}