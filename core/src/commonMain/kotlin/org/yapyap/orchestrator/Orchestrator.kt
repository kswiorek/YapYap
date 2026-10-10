package org.yapyap.orchestrator

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.runtime.OrchestratorRuntime


public interface Orchestrator {
    public val state: StateFlow<OrchestratorState>

    /**
     * Newcomer onboarding lifecycle, in every mode (headless relays have no runtime, so this —
     * plus the log — is their whole onboarding UX; the GUI reads the same flow through the
     * runtime service). IDLE when no onboarding ever ran or after cancel/complete.
     */
    public val onboardingState: StateFlow<OnboardingState>

    /** Boot recovery → start router → start domain loops. */
    public suspend fun start()

    public suspend fun stop()

    public suspend fun completeSetup(intent: SetupIntent): SetupResult

    /**
     * Terminal offline wipe: deletes local persistence (`vault.db*`, keyring
     * entries, `tor/`, `state.toml`) and returns to
     * [OrchestratorState.SetupRequired]. Callable only from
     * [OrchestratorState.ResetRequired], `Stopped`, `Failed` or `SetupRequired`;
     * refuses in `Running`/`Starting`.
     */
    public suspend fun resetApp()

    /**
     * Domain APIs. Prefer throwing/checking state over nullable returns
     * so misuse fails fast in tests.
     */
    public fun runtime(): OrchestratorRuntime
}