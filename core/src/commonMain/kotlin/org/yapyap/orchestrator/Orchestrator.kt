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

    /**
     * Coarse boot progress, for progress UX while [state] is `Starting`.
     * Updated as `start()`/`completeSetup()` move through [BootPhase]s; the room
     * sweep additionally reports [BootProgress.roomsDone]/[BootProgress.roomsTotal].
     * Ignore unless [state] is `Starting`.
     */
    public val bootProgress: StateFlow<BootProgress>

    /**
     * Local Tor publish readiness: whether our own onion is dialable yet.
     * WARMING_UP after every start (the descriptor is re-uploaded per boot)
     * until an HSDir accepts it; PUBLISHED latches then. Headless relays watch
     * this like [onboardingState] — it is node-level, not account-level.
     */
    public val transportState: StateFlow<LocalTransportState>

    /** Boot recovery → start router → start domain loops. */
    public suspend fun start()

    public suspend fun stop()

    public suspend fun completeSetup(intent: SetupIntent): SetupResult

    /**
     * Terminal offline wipe: deletes local persistence (`vault.db*`, keyring
     * entries, `tor/`, `state.toml`, `logs/`) and lands in
     * [OrchestratorState.Stopped]. Callable only from
     * [OrchestratorState.ResetRequired], `Stopped`, `Failed` or `SetupRequired`;
     * refuses in `Running`/`Starting`. Call [start] next: it reopens the
     * database, recreates the schema/directories, and re-diagnoses into
     * `SetupRequired` on the fresh empty directory.
     */
    public suspend fun resetApp()

    /**
     * Domain APIs. Prefer throwing/checking state over nullable returns
     * so misuse fails fast in tests.
     */
    public fun runtime(): OrchestratorRuntime
}