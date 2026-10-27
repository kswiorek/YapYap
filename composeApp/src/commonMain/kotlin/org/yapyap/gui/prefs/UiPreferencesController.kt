package org.yapyap.gui.prefs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns [UiPreferences]: reads once at startup (before first frame), exposes a
 * hot StateFlow, writes debounced (250 ms) on change. Persistence is a plain
 * file (`dataDirectory/ui-prefs.json`) — GUI-owned, never the encrypted vault DB.
 */
class UiPreferencesController(
    private val scope: CoroutineScope,
    private val readFile: () -> String?,
    private val writeFile: (String) -> Unit,
) {
    private val _prefs = MutableStateFlow(
        readFile()?.let { decodeUiPreferences(it) } ?: UiPreferences.DEFAULT,
    )
    val prefs: StateFlow<UiPreferences> = _prefs.asStateFlow()

    private var pendingWrite: Job? = null

    fun update(transform: (UiPreferences) -> UiPreferences) {
        _prefs.value = transform(_prefs.value)
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            delay(250.milliseconds)
            runCatching { writeFile(_prefs.value.encode()) }
        }
    }

    /** Flush synchronously (app shutdown). */
    fun flush() {
        pendingWrite?.cancel()
        runCatching { writeFile(_prefs.value.encode()) }
    }
}
