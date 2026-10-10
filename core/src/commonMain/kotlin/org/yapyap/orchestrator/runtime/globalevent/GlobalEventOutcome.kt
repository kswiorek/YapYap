package org.yapyap.orchestrator.runtime.globalevent

/**
 * Shared outcome of GUI-facing global-event publishes (admin mutations and
 * self-service alike). Domain refusals are values; infrastructure failures
 * (transport, storage) still throw.
 */
public sealed interface GlobalEventOutcome {
    /** Appended to GLOBAL + broadcast; local fold already committed. */
    public data object Published : GlobalEventOutcome

    /** Refused before any write — nothing appended, nothing broadcast. */
    public data class Refused(val reason: GlobalEventRefusal) : GlobalEventOutcome
}

public sealed interface GlobalEventRefusal {
    /** Local account lacks the admin flag (incl. the revocation race). */
    public data object NotAdmin : GlobalEventRefusal

    /** GLOBAL frontier unchainable — still syncing. */
    public data object NotReady : GlobalEventRefusal

    /** No such account/device row. */
    public data object TargetNotFound : GlobalEventRefusal

    /** Self-service target belongs to another account. */
    public data object NotOwnDevice : GlobalEventRefusal

    /** Newcomer onboarding in flight — the fold has not anchored the local device yet. */
    public data object OnboardingActive : GlobalEventRefusal

    /** Malformed owner-handover shapes (fail-closed, mirroring the room tier's
     * `InvalidSuccessor`): the owner leaving without a successor, an unknown/inactive/
     * device-less successor, the leaver as successor, or a successor on a non-owner leave. */
    public data object InvalidSuccessor : GlobalEventRefusal

    /** Admin op targeting the owner or the owner's devices (irrevocable — the network's
     * repair path). The owner acts on their own account via the self-service paths. */
    public data object OwnerIrrevocable : GlobalEventRefusal

    /** Removing the account's last confirmed device without proving possession of the
     * recovery key — the GUI gates this on `verifyRecoveryKey`. */
    public data object RecoveryKeyRequired : GlobalEventRefusal

    /** Target exists but its current state makes the op a fold no-op: a BANNED
     *  account, an already-admin (or owner) grant target, a non-admin revoke
     *  target. The fold stores these VERIFIED with no shadow effect — this
     *  refusal is the GUI's only feedback. */
    public data object TargetNotEligible : GlobalEventRefusal

    /** Admin op targeting the local account's own rows — the self-service paths
     *  ([org.yapyap.orchestrator.runtime.account.AccountService]) own those and
     *  carry gates the admin path cannot: the owner-handover successor and the
     *  last-device recovery key. */
    public data object SelfServiceRequired : GlobalEventRefusal
}
