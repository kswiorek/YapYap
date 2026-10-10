package org.yapyap.orchestrator.runtime.onboarding

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.onboarding.OnboardingProvider
import org.yapyap.orchestrator.onboarding.OnboardingState

/**
 * GUI-facing onboarding service (mirrors [org.yapyap.orchestrator.runtime.message.MessagingService]),
 * sponsor side only. The newcomer side lives in the orchestrator-level
 * [org.yapyap.orchestrator.onboarding.OnboardingProvider], which runs on every node including headless
 * relays; this service exposes the newcomer's progress to the GUI ([newcomerState]) and drives the
 * interactive sponsor flow (scanning a newcomer's QR identity payload out-of-band).
 */
public interface OnboardingService {
    /** Newcomer-side onboarding progress, delegated from the [OnboardingProvider]. */
    public val newcomerState: StateFlow<OnboardingState>

    /**
     * Sponsor side: onboard a newcomer whose QR invite was scanned out-of-band. The
     * [inviteBytes] are decoded internally and authoritative about the target account;
     * undecodable bytes are refused as `MalformedInvite(MALFORMED_BYTES)` — ask for a
     * fresh code, don't crash. UI-mode mismatches degrade gracefully:
     *  - `invite.account == null` → the newcomer joins the sponsor's existing account: append only
     *    `AddDevice`, bound to the sponsor's local account. [admin] is meaningless here — the
     *    device is still added, reported via `Sponsored(adminGranted = false)`;
     *  - otherwise → a new account: append `AddAccount` + `AddDevice` back-to-back, and when
     *    [admin] is true also append `GrantAdmin`, which requires the sponsor to be an admin.
     *
     * Returns a [SponsorOutcome]: refusals (malformed invite, non-admin sponsor, sponsor not
     * synced yet) happen BEFORE any write — nothing is appended and no intro is sent.
     * Infrastructure failures (transport, storage) still throw.
     */
    public suspend fun sponsorNewcomer(inviteBytes: ByteArray, admin: Boolean = false): SponsorOutcome

    /**
     * GUI cancel button for an abandoned onboarding (newcomer or sponsor side): delegates to
     * [OnboardingProvider.cancelOnboarding].
     */
    public suspend fun newcomerCancelOnboarding()
}
