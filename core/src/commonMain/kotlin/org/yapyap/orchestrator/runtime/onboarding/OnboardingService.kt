package org.yapyap.orchestrator.runtime.onboarding

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.IdentityPayload
import org.yapyap.routing.router.BootstrapIntroEvent

/** Onboarding lifecycle state, surfaced to the GUI. */
enum class OnboardingState {
    /** No onboarding in progress (or already complete — the one-time secret is burned). */
    IDLE,

    /** The newcomer is waiting for the sponsor's intro, or the sponsor is awaiting its first sync. */
    AWAITING_INTRO,

    /** The bootstrap intro was received and the global room is syncing. */
    SYNCING,

    /** The first fold containing the local device's own Add event landed; the secret is burned. */
    COMPLETE,
}

/**
 * GUI-facing onboarding service (mirrors [org.yapyap.orchestrator.runtime.message.MessagingService]).
 * Holds both directions of the bootstrap flow:
 *  - newcomer side: consumes authenticated [BootstrapIntroEvent]s emitted by the router;
 *  - sponsor side: onboards a newcomer whose QR identity payload was scanned.
 *
 * This is scaffolding for the sprint-4 onboarding handshake — the persistence and sync wiring is
 * still open and implemented with TODO bodies (see the global-events design doc, §8).
 */
interface OnboardingService {
    /** Onboarding lifecycle state. */
    val state: StateFlow<OnboardingState>

    /**
     * Newcomer side: handle an authenticated bootstrap intro received from a sponsor.
     *
     * TODO(sprint 4 onboarding): insert the sponsor's provisional account + device + GLOBAL
     * membership rows (insert-only, FK order, provisional flag), then trigger
     * `requestRangeSync(GLOBAL, 0..event.payload.dagHeadLamport)` so the synced DAG confirms/clears
     * the provisional rows.
     */
    suspend fun onBootstrapIntro(event: BootstrapIntroEvent)

    /**
     * Sponsor side: onboard a newcomer whose QR identity payload was scanned out-of-band.
     *
     * TODO(sprint 4 onboarding): set the one-time shared secret on the
     * [org.yapyap.orchestrator.onboarding.BootstrapSessionStore], insert the newcomer's peer rows,
     * append `AddAccount` + `AddDevice` back-to-back to the global DAG, then send the intro via
     * [org.yapyap.routing.router.Router.sendBootstrapIntro].
     */
    suspend fun sponsorNewcomer(sharedSecret: ByteArray, newcomerIdentity: IdentityPayload)
}