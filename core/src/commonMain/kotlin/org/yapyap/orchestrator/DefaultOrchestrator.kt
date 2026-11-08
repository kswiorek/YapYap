package org.yapyap.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.boot.BootConfig
import org.yapyap.orchestrator.boot.BootDiagnosis
import org.yapyap.orchestrator.boot.DataDirLock
import org.yapyap.orchestrator.boot.DataDirectoryInUseException
import org.yapyap.orchestrator.boot.Node
import org.yapyap.orchestrator.boot.NodeAssembler
import org.yapyap.orchestrator.boot.Vault
import org.yapyap.orchestrator.boot.acquireDataDirLock
import org.yapyap.orchestrator.boot.readDataDirLockHolder
import org.yapyap.orchestrator.runtime.OrchestratorRuntime

/**
 * The orchestrator's lifecycle: boot diagnosis, setup, start/stop, self-ban and reset. Every
 * component comes from [assembler]; this class only decides when a vault and a node exist.
 */
internal class DefaultOrchestrator(
    private val dataDirectory: Path,
    private val bootConfig: BootConfig,
    private val assembler: NodeAssembler,
) : Orchestrator {

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Created)
    override val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    // Stable across start cycles; mirrored from the current node, which is rebuilt per start.
    private val _onboardingState = MutableStateFlow(OnboardingState.IDLE)
    override val onboardingState: StateFlow<OnboardingState> = _onboardingState.asStateFlow()

    private val _transportStatus = MutableStateFlow<LocalTransportStatus>(INITIAL_TRANSPORT_STATUS)
    override val transportStatus: StateFlow<LocalTransportStatus> = _transportStatus.asStateFlow()

    private val _bootProgress = MutableStateFlow(BootProgress(BootPhase.STARTING, 0, 0))
    override val bootProgress: StateFlow<BootProgress> = _bootProgress.asStateFlow()

    /** What one start opened: its scope, the vault, and the node once assembled. */
    private class Session(val scope: CoroutineScope, val vault: Vault) {
        var node: Node? = null
    }

    /** The current start's resources; kept while Stopped (the data is still ours) until a restart or wipe. */
    private var session: Session? = null

    /**
     * The data-directory lock, held from the first successful [start] until [resetApp]
     * wipes the directory (deliberately *not* released by [stop]: the data is still
     * ours while Stopped, and a second process must stay out).
     */
    private var dataDirLock: DataDirLock? = null

    /** Entering Starting: reset the boot-progress flow alongside the state. */
    private fun markStarting() {
        _bootProgress.value = BootProgress(BootPhase.STARTING, 0, 0)
        _state.value = OrchestratorState.Starting
    }

    /** Throws naming the live holder recorded in the lock file (null when unreadable). */
    private fun dataDirectoryInUse(): Nothing =
        throw DataDirectoryInUseException(dataDirectory.toString(), readDataDirLockHolder(dataDirectory))

    override suspend fun start() {
        if (_state.value is OrchestratorState.Running || _state.value is OrchestratorState.Starting) return
        // Idempotent restart: a previous start() (SetupRequired/Stopped/Failed) left a scope and
        // an open database behind. Close them before reopening so a second start() never leaks
        // a JDBC/SQLCipher handle or double-opens vault.db.
        closeSession()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        markStarting()
        try {
            assembler.prepareDataDirectory()

            // Single-instance guard: a held lock means a *live* holder (the OS releases it on
            // process death), so fail fast — through the standard catch below, which logs and
            // lands in Failed — instead of contending on vault.db with it.
            if (dataDirLock == null) {
                dataDirLock = acquireDataDirLock(dataDirectory) ?: dataDirectoryInUse()
            }

            _bootProgress.value = BootProgress(BootPhase.DATABASE, 0, 0)
            val vault = assembler.openVault(scope)
            val current = Session(scope, vault)
            session = current
            val diagnosis = vault.bootDiagnoser.diagnose(storeIsNew = vault.storeIsNew)
            if (diagnosis is BootDiagnosis.ResetRequired) {
                AppLog.warn(
                    component = LogComponent.ORCHESTRATOR,
                    event = LogEvent.BOOT_DIAGNOSED,
                    message = "Boot diagnosis requires a reset",
                    fields = mapOf("reason" to diagnosis.reason.name, "details" to diagnosis.details),
                )
            }
            when (diagnosis) {
                is BootDiagnosis.Healthy -> {
                    markStarting()
                    startNode(current)
                    _state.value = OrchestratorState.Running
                }

                is BootDiagnosis.SetupRequired -> _state.value = OrchestratorState.SetupRequired

                is BootDiagnosis.ResetRequired ->
                    _state.value = OrchestratorState.ResetRequired(diagnosis.reason, diagnosis.details)
            }
        } catch (e: Throwable) {
            AppLog.error(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.SESSION_FAILED,
                message = "Orchestrator start failed",
                throwable = e,
            )
            _state.value = OrchestratorState.Failed(e)
        }
    }

    override suspend fun completeSetup(intent: SetupIntent): SetupResult {
        require(state.value is OrchestratorState.SetupRequired) { "Orchestrator must be in SetupRequired state" }
        val current = checkNotNull(session) { "SetupRequired without an open vault" }
        val prepared = current.vault.setupPreparer.prepare(intent)
        markStarting()
        val node = startNode(current)
        node.setup.anchor(prepared)
        _state.value = OrchestratorState.Running
        return node.setup.complete(prepared)
    }

    /** Assembles and starts the node on [current]'s vault, mirroring its flows into the stable ones. */
    private suspend fun startNode(current: Session): Node {
        val node = current.vault.assembleNode(current.scope)
        current.node = node
        current.scope.launch { node.onboardingState.collect { _onboardingState.value = it } }
        current.scope.launch { node.transportStatus.collect { _transportStatus.value = it } }
        current.scope.launch { node.resetRequests.collect { enterResetRequired(it.reason, it.details) } }
        node.start { _bootProgress.value = it }
        return node
    }

    override suspend fun stop() {
        if (_state.value !is OrchestratorState.Running) return
        _state.value = OrchestratorState.Stopping

        try {
            session?.node?.stop()
            session?.scope?.cancel()
            _state.value = OrchestratorState.Stopped
        } catch (e: Throwable) {
            AppLog.error(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.SESSION_FAILED,
                message = "Orchestrator stop failed",
                throwable = e,
            )
            _state.value = OrchestratorState.Failed(e)
        }
    }

    /**
     * Self-ban / onboarding-expiry teardown: unlike [stop] (which ends in `Stopped`), this lands
     * in [OrchestratorState.ResetRequired] and keeps the DB open so the next `start()` can
     * diagnose the tombstoned rows (closing happens at wipe time in [resetApp]). State flips
     * first so the GUI blocks even if a transport hangs; every teardown step is best-effort and
     * teardown failures stay in the log — the ban fact doesn't change because a socket complained.
     * No-op unless currently `Running` (a single ban emits both `DeviceRemoved` and
     * `AccountRemoved`). An onboarding deadline expiring between diagnose() and Running is
     * skipped here and caught at the next boot by the diagnoser — a one-boot delay in a tiny window.
     */
    private suspend fun enterResetRequired(reason: ResetReason, details: String) {
        if (_state.value !is OrchestratorState.Running) return
        _state.value = OrchestratorState.ResetRequired(reason, details)
        AppLog.error(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.SESSION_FAILED,
            message = "Local identity unusable, entering ResetRequired",
            fields = mapOf("reason" to reason.name, "details" to details),
        )
        val current = session ?: return
        runCatching { current.node?.stop() }
        runCatching { current.scope.cancel() }
    }

    override suspend fun resetApp() {
        check(
            _state.value is OrchestratorState.ResetRequired ||
                    _state.value is OrchestratorState.Stopped ||
                    _state.value is OrchestratorState.Failed ||
                    _state.value is OrchestratorState.SetupRequired
        ) { "resetApp() requires ResetRequired, Stopped, Failed or SetupRequired (was ${_state.value})" }
        // Never wipe under a live holder: without a self-held lock, a failed
        // acquire means another live instance owns the directory — refuse instead
        // of deleting its vault out from under it.
        if (dataDirLock == null) {
            dataDirLock = acquireDataDirLock(dataDirectory) ?: dataDirectoryInUse()
        }
        val current = session
        current?.let { runCatching { it.scope.cancel() } }
        // The wipe closes the database itself, after reading the key refs it still holds.
        assembler.storeReset(current?.vault).wipe()
        session = null
        // The wipe leaves the lock file (held open throughout); release it now and forget
        // the handle so the next start() re-acquires.
        runCatching { dataDirLock?.close() }
        dataDirLock = null
        _onboardingState.value = OnboardingState.IDLE
        _transportStatus.value = INITIAL_TRANSPORT_STATUS
        // Land in Stopped, not SetupRequired: the wiped vault is gone, and only start() can
        // reopen the directory (it recreates the schema and re-diagnoses into SetupRequired).
        // Drop the dead logger with it.
        AppLog.reset()
        _state.value = OrchestratorState.Stopped
    }

    override fun runtime(): OrchestratorRuntime {
        check(bootConfig.mode == NodeMode.FULL_CLIENT) { "runtime() requires FULL_CLIENT mode" }
        check(_state.value is OrchestratorState.Running) { "Orchestrator must be Running" }
        return checkNotNull(session?.node?.runtime) { "Running without a runtime" }
    }

    /** Cancels the previous start's scope and closes its database, if any. */
    private fun closeSession() {
        val previous = session ?: return
        runCatching { previous.scope.cancel() }
        runCatching { previous.vault.close() }
        session = null
    }
}
