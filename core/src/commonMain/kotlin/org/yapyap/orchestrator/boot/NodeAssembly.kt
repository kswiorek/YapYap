package org.yapyap.orchestrator.boot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.BootProgress
import org.yapyap.orchestrator.LocalTransportStatus
import org.yapyap.orchestrator.onboarding.LocalSetup
import org.yapyap.orchestrator.onboarding.LocalSetupPreparer
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.runtime.OrchestratorRuntime

/**
 * What the orchestrator's lifecycle needs from the composition root. The orchestrator
 * declares it; the wiring layer implements it, so the orchestrator never constructs
 * stores, transports or the router itself.
 *
 * Two phases, each built fresh per start: a [Vault] (the data directory's stores, usable
 * before any local identity exists) and a [Node] on top of it (everything that needs the
 * local identity).
 */
internal interface NodeAssembler {
    /** Creates the data directory layout and initializes logging. Idempotent. */
    fun prepareDataDirectory()

    /**
     * Opens config, key store, database and identity; background work (config file watching)
     * runs in [scope]. The data-directory lock must be held.
     */
    suspend fun openVault(scope: CoroutineScope): Vault

    /** The wipe for [resetApp][org.yapyap.orchestrator.Orchestrator.resetApp]; [vault] is null when none opened. */
    fun storeReset(vault: Vault?): LocalStoreReset
}

internal interface Vault {
    /** True when this open created the database (nothing was there before). */
    val storeIsNew: Boolean

    val bootDiagnoser: BootDiagnoser

    val setupPreparer: LocalSetupPreparer

    /** Builds every component of a running node; nothing starts until [Node.start]. */
    suspend fun assembleNode(scope: CoroutineScope): Node

    fun close()
}

internal interface Node {
    val onboardingState: StateFlow<OnboardingState>
    val transportStatus: StateFlow<LocalTransportStatus>

    /** The local identity became unusable while running; see [org.yapyap.orchestrator.LocalIdentityWatch]. */
    val resetRequests: Flow<ResetRequest>

    val setup: LocalSetup

    /** Null in modes without a GUI (headless relays). */
    val runtime: OrchestratorRuntime?

    /** Starts every component in dependency order, reporting [BootProgress]; returns when the node is ready. */
    suspend fun start(onProgress: (BootProgress) -> Unit)

    suspend fun stop()
}

internal data class ResetRequest(val reason: ResetReason, val details: String)
