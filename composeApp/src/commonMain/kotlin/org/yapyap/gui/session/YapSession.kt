package org.yapyap.gui.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path
import org.yapyap.gui.prefs.UiPreferencesController
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

    fun showToast(text: String, kind: ToastKind = ToastKind.Info) {
        _toast.value = ToastMessage(text, kind, Clock.System.now().toEpochMilliseconds())
    }

    fun clearToast() {
        _toast.value = null
    }

    /** Create the orchestrator and boot. Safe to call once; later calls no-op. */
    fun start() {
        if (orchestrator != null) return
        val orch = OrchestratorFactory(dataDirectory, NodeMode.FULL_CLIENT).create()
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
}

enum class ToastKind { Info, Success, Error }

data class ToastMessage(val text: String, val kind: ToastKind, val atMillis: Long)
