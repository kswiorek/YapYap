package org.yapyap.orchestrator.runtime.onboarding

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.onboarding.BootstrapSessionStore
import org.yapyap.orchestrator.onboarding.OnboardingProvider
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.routing.router.Router

/**
 * Sponsor-side onboarding service, wired to the orchestrator-level [OnboardingProvider] for the
 * newcomer state it exposes to the GUI. Holds only the GUI-facing sponsor flow (QR scan -> sponsor);
 * the background newcomer work is done by the provider, which also runs on headless relays.
 */
internal class DefaultOnboardingService(
    private val provider: OnboardingProvider,
    private val router: Router,
    private val sessionStore: BootstrapSessionStore,
) : OnboardingService {

    override val newcomerState: StateFlow<OnboardingState> = provider.state

    override suspend fun sponsorNewcomer(invite: Invite, admin: Boolean) {
        require(invite.account != null || !admin) { "admin toggle applies only to new accounts" }
        // TODO(sprint 4 onboarding): the invite's account presence drives the sponsor flow:
        //   sessionStore.setActiveSecret(invite.sharedSecret) — one-time secret, burned at COMPLETE;
        //   insert the newcomer's peer rows — device always; and when invite.account != null also the
        //     provisional account row with is_admin = admin (we know it here, so write it as marked);
        //   append global events — invite.account == null -> AddDevice bound to the local account;
        //     else re-derive accountId from the account public key, then AddAccount + AddDevice
        //     back-to-back (same signer, §3 of the global-events doc), and when admin == true also
        //     append GrantAdmin (valid only if the local account is admin at that fold position —
        //     fail fast on the local is_admin here; the GUI shows the toggle only for admins);
        //   router.sendBootstrap(Intro(...)) with the sponsor's identity + DAG head.
        TODO("sprint 4 onboarding: sponsor flow not yet implemented")
    }
}
