package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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
 * Orchestrator-level newcomer onboarding. Runs on every node, including headless relays (which have
 * no [org.yapyap.orchestrator.runtime.OrchestratorRuntime]), because a relay must be able to onboard
 * itself as a newcomer too: it consumes the sponsor's authenticated bootstrap intros from the router,
 * seeds the sponsor's provisional identity rows, and triggers the global-room range sync.
 *
 * The sponsor side (GUI-only — QR scanning) lives in the runtime's onboarding service, not here.
 * This service is scaffolding for the sprint-4 onboarding handshake (see the global-events design
 * doc, §8); the persistence and sync wiring are still TODO.
 */
interface OnboardingProvider {
    /** Onboarding lifecycle state. */
    val state: StateFlow<OnboardingState>

    fun start(scope: CoroutineScope)

    suspend fun stop()

    /**
     * Abandon onboarding: burns the one-time secret and returns to [OnboardingState.IDLE].
     * All-modes primitive — the GUI calls it via the runtime onboarding service's passthrough,
     * headless operators via the CLI (or wipe-the-dir, which [completeSetup][org.yapyap.orchestrator.DefaultOrchestrator.completeSetup]
     * makes safe by burning first). Covers newcomer and sponsor abandonment alike (single-slot secret).
     */
    suspend fun cancelOnboarding()
}
