package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/** Onboarding lifecycle state, surfaced to the GUI. */
public enum class OnboardingState {
    /** No onboarding in progress (or already complete — the one-time secret is burned). */
    IDLE,

    /** This node is onboarding as a newcomer and waiting for the sponsor's intro. */
    AWAITING_INTRO,

    /**
     * Recovery path only: our recovery request is queued to the device we asked and
     * not yet confirmed. (On the invite path the sponsor contacts us instead, so
     * there is no request — that path stays in [AWAITING_INTRO].)
     */
    REQUEST_SENT,

    /** Recovery path only: the device we asked confirmed our request; waiting for its intro. */
    REQUEST_DELIVERED,

    /** The bootstrap intro was received and the global room is syncing. */
    SYNCING,

    /** The first fold containing the local device's own Add event landed; the secret is burned. */
    COMPLETE,

    /**
     * Deadline passed before completion; secret burned. Terminal: no in-place retry exists
     * (setup requires SetupRequired state; the sponsor already published our AddDevice, so
     * same-key retry would be an invalid duplicate; the burned secret can never open the
     * intro). Lands in `OrchestratorState.ResetRequired(ONBOARDING_EXPIRED)` — recovery is
     * `resetApp()` plus a fresh setup.
     */
    TIMED_OUT,
}

/**
 * Newcomer onboarding on every node (headless relays onboard as newcomers too). Sole writer
 * of the session slot ([org.yapyap.persistence.key.BootstrapSessionStore]); the sponsor side
 * is stateless and lives in the runtime service.
 */
internal interface OnboardingProvider {
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
     * Recovery path: [packetId] is our recovery request, just queued. Enters
     * REQUEST_SENT, then REQUEST_DELIVERED once the device it went to acknowledges it.
     */
    suspend fun awaitRequestDelivery(packetId: Uuid)

    /**
     * Burn the secret, back to IDLE. All-modes primitive (GUI passthrough, headless CLI);
     * [completeSetup][org.yapyap.orchestrator.DefaultOrchestrator.completeSetup] also uses it
     * to clear stale sessions. Newcomer-only.
     */
    suspend fun cancelOnboarding()
}
