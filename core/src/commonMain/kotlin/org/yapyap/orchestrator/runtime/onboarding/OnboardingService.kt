package org.yapyap.orchestrator.runtime.onboarding

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.onboarding.OnboardingProvider
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.protocol.envelopes.Invite

/**
 * GUI-facing onboarding service (mirrors [org.yapyap.orchestrator.runtime.message.MessagingService]),
 * sponsor side only. The newcomer side lives in the orchestrator-level
 * [org.yapyap.orchestrator.onboarding.OnboardingProvider], which runs on every node including headless
 * relays; this service exposes the newcomer's progress to the GUI ([newcomerState]) and drives the
 * interactive sponsor flow (scanning a newcomer's QR identity payload out-of-band).
 *
 * This is scaffolding for the sprint-4 onboarding handshake — the persistence and sync wiring is
 * still open and implemented with TODO bodies (see the global-events design doc, §8).
 */
interface OnboardingService {
    /** Newcomer-side onboarding progress, delegated from the [OnboardingProvider]. */
    val newcomerState: StateFlow<OnboardingState>

    /**
     * Sponsor side: onboard a newcomer whose QR invite was scanned out-of-band. [invite] is the
     * decoded INVITE-flavor payload; the sponsor acts on its own account:
     *  - `invite.account == null` → the newcomer joins the sponsor's existing account: append only
     *    `AddDevice`, bound to the sponsor's local account ([admin] is rejected here — the account
     *    exists and its status doesn't change on device-add);
     *  - otherwise → a new account: append `AddAccount` + `AddDevice` back-to-back, and when
     *    [admin] is true also append `GrantAdmin` (only meaningful local accounts can grant it).
     *
     * TODO(sprint 4 onboarding): set the one-time shared secret on the
     * [org.yapyap.orchestrator.onboarding.BootstrapSessionStore], insert the newcomer's peer rows
     * (provisional account with `is_admin = admin` when a new account), append the
     * global-`AddAccount`/`AddDevice`/`GrantAdmin` events (typed codec — global events work), then
     * send the intro via [org.yapyap.routing.router.Router.sendBootstrap].
     */
    suspend fun sponsorNewcomer(invite: Invite, admin: Boolean = false)
}
