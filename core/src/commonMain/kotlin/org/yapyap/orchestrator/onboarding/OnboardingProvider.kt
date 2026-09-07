package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Onboarding lifecycle state, surfaced to the GUI. */
enum class OnboardingState {
    /** No onboarding in progress (or already complete — the one-time secret is burned). */
    IDLE,

    /** This node is onboarding as a newcomer and waiting for the sponsor's intro. */
    AWAITING_INTRO,

    /** The bootstrap intro was received and the global room is syncing. */
    SYNCING,

    /** The first fold containing the local device's own Add event landed; the secret is burned. */
    COMPLETE,

    /**
     * Deadline passed before completion; secret burned. Terminal until the next setup run —
     * surfaced (not silent IDLE) so the GUI can show "didn't complete, try again".
     */
    TIMED_OUT,
}

/**
 * Newcomer onboarding on every node (headless relays onboard as newcomers too). Sole writer
 * of the session slot ([org.yapyap.persistence.key.BootstrapSessionStore]); the sponsor side
 * is stateless and lives in the runtime service.
 */
interface OnboardingProvider {
    /** Onboarding lifecycle state. */
    val state: StateFlow<OnboardingState>

    /**
     * Attach to [scope]; resume only if a persisted session exists, else stay dormant until
     * [beginSession]. The AEAD gate is open ⟺ the provider runs, so nothing is lost dormant.
     */
    fun start(scope: CoroutineScope)

    suspend fun stop()

    /**
     * Persist the newcomer's secret with its deadline, enter AWAITING_INTRO, arm the timer.
     * The caller embeds the secret in the QR invite / recovery request.
     */
    suspend fun beginSession(secret: ByteArray)

    /**
     * Burn the secret, back to IDLE. All-modes primitive (GUI passthrough, headless CLI);
     * [completeSetup][org.yapyap.orchestrator.DefaultOrchestrator.completeSetup] also uses it
     * to clear stale sessions. Newcomer-only.
     */
    suspend fun cancelOnboarding()
}
