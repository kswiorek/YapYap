package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.OnboardingState
import kotlin.uuid.Uuid

/**
 * Newcomer onboarding on every node (headless relays onboard as newcomers too). Sole writer
 * of the session slot ([org.yapyap.crypto.store.BootstrapSessionStore]); the sponsor side
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
