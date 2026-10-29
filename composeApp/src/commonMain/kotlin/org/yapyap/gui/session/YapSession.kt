package org.yapyap.gui.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path
import org.yapyap.gui.prefs.UiPreferencesController
import org.yapyap.orchestrator.LoopbackTransport
import org.yapyap.orchestrator.NodeMode
import org.yapyap.orchestrator.Orchestrator
import org.yapyap.orchestrator.OrchestratorFactory
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Process-level session: owns the [Orchestrator], the app [CoroutineScope], and
 * the [UiPreferencesController]. One per process — created in main()/Application/
 * MainViewController, never in composition.
 */
class YapSession(
    val dataDirectory: Path,
    val loopback: LoopbackTransport = LoopbackTransport.NONE,
    val keyringNamespace: String? = null,
    val notificationSink: NotificationSink = NotificationSink.NoOp,
    readPrefs: () -> String?,
    writePrefs: (String) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefsController = UiPreferencesController(scope, readPrefs, writePrefs)

    @Volatile
    var orchestrator: Orchestrator? = null
        private set
    private val _orchestratorFlow = MutableStateFlow<Orchestrator?>(null)

    /** Collectable handle — plain `orchestrator` is only set once and never triggers recomposition. */
    val orchestratorFlow: StateFlow<Orchestrator?> = _orchestratorFlow.asStateFlow()

    private val _toast = MutableStateFlow<ToastMessage?>(null)
    val toast: StateFlow<ToastMessage?> = _toast.asStateFlow()

    // Setup outputs that must survive navigation: completeSetup() flips the
    // orchestrator to Running BEFORE returning, so SetupScreen is already gone
    // when the result arrives. Park them here; AppRoot/MainShell render them.
    private val _pendingRecoveryKey = MutableStateFlow<String?>(null)
    val pendingRecoveryKey: StateFlow<String?> = _pendingRecoveryKey.asStateFlow()

    private val _pendingInviteBytes = MutableStateFlow<ByteArray?>(null)
    val pendingInviteBytes: StateFlow<ByteArray?> = _pendingInviteBytes.asStateFlow()

    fun acknowledgeRecoveryKey() {
        _pendingRecoveryKey.value = null
    }

    fun clearPendingInvite() {
        _pendingInviteBytes.value = null
    }

    /** Parks setup outputs for the App-root overlays (see _pendingRecoveryKey). */
    fun stageSetupResult(recoveryKey: String?, inviteBytes: ByteArray?) {
        if (recoveryKey != null) _pendingRecoveryKey.value = recoveryKey
        if (inviteBytes != null) _pendingInviteBytes.value = inviteBytes
    }

    fun showToast(text: String, kind: ToastKind = ToastKind.Info) {
        _toast.value = ToastMessage(text, kind, Clock.System.now().toEpochMilliseconds())
    }

    fun clearToast() {
        _toast.value = null
    }

    /**
     * Full wipe + reboot: stops first (core resetApp() refuses
     * Running/Starting), wipes to Stopped, then boots back into
     * SetupRequired. Failures surface as toasts — never silent.
     */
    fun resetAndReboot() {
        scope.launch {
            val orch = orchestrator
            if (orch == null) {
                showToast("Nothing to reset", ToastKind.Error)
                return@launch
            }
            runCatching { orch.stop() }
            val reset = runCatching { orch.resetApp() }
            if (reset.isFailure) {
                showToast("Reset failed: ${reset.exceptionOrNull()?.message}", ToastKind.Error)
                return@launch
            }
            runCatching { orch.start() }.onFailure {
                showToast("Restart failed: ${it.message}", ToastKind.Error)
            }
        }
    }
    /** Create the orchestrator and boot. Safe to call repeatedly; re-calls re-run start(). */
    fun start() {
        val existing = orchestrator
        if (existing != null) {
            // New core flow: resetApp() lands in Stopped, so Restart must re-run
            // start() on the same instance (it reopens vault.db and re-diagnoses
            // into SetupRequired). Core start() is idempotent.
            scope.launch {
                runCatching { existing.start() }.onFailure { showToast("Startup failed: ${it.message}") }
            }
            return
        }
        val orch = OrchestratorFactory(dataDirectory, NodeMode.FULL_CLIENT, loopback, keyringNamespace).create()
        orchestrator = orch
        _orchestratorFlow.value = orch
        scope.launch {
            runCatching { orch.start() }.onFailure { showToast("Startup failed: ${it.message}") }
        }
    }

    fun stop() {
        scope.launch {
            runCatching { orchestrator?.stop() }
            prefsController.flush()
        }.invokeOnCompletion { scope.cancel() }
    }

    /**
     * Inline teardown for process shutdown (window close): same work as [stop]
     * but awaited, so the JVM must not die mid-teardown and orphan the Tor
     * daemon (its data-dir lock would break the *next* launch with a dead
     * control connection). Callers cap it with a timeout and exit regardless.
     */
    suspend fun awaitStop() {
        runCatching { orchestrator?.stop() }
        runCatching { prefsController.flush() }
        runCatching { scope.cancel() }
    }
}

enum class ToastKind { Info, Success, Error }

data class ToastMessage(val text: String, val kind: ToastKind, val atMillis: Long)
