package org.yapyap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.capabilities.Capabilities
import org.yapyap.gui.capabilities.LocalCapabilities
import org.yapyap.gui.components.ToastHost
import org.yapyap.gui.components.YapButton
import org.yapyap.gui.components.YapButtonVariant
import org.yapyap.gui.prefs.UiPreferences
import org.yapyap.gui.screens.reset.FailureScreen
import org.yapyap.gui.screens.reset.ResetScreen
import org.yapyap.gui.screens.setup.RecoveryKeyModal
import org.yapyap.gui.screens.setup.SetupScreen
import org.yapyap.gui.screens.shell.MainShell
import org.yapyap.gui.session.ToastKind
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.theme.YapTheme
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.BootPhase
import org.yapyap.orchestrator.BootProgress
import org.yapyap.orchestrator.Orchestrator
import org.yapyap.orchestrator.OrchestratorState
import yapyap.composeapp.generated.resources.* // Res + generated string accessors

@Composable
fun App(session: YapSession?) {
    YapTheme {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalCapabilities provides Capabilities(),
        ) {
            if (session == null) {
                SplashScreen("YapYap")
            } else {
                val prefs by session.prefsController.prefs.collectAsState()
                WithUiZoom(prefs) {
                    val orchState by session.orchestratorFlow.collectAsState()
                    val orch = orchState
                    if (orch == null) {
                        LaunchedEffect(session) { session.start() }
                        SplashScreen(stringResource(Res.string.splash_starting))
                    } else {
                        val state by orch.state.collectAsState()
                        AppRoot(session, orch, state)
                    }
                }
            }
        }
    }
}

/**
 * Universal UI scale (Settings → Appearance → Interface zoom): overriding
 * LocalDensity scales every dp AND sp below this point, so a whole subtree
 * zooms with no per-element plumbing. Raw px (borders drawn in pixels, AWT
 * drag/resize math) intentionally do not scale. The window itself keeps its
 * size — zoomed content scrolls. Shared by App content (all platforms) and the
 * desktop title bar / resize overlay in main.kt (siblings, so no double zoom).
 */
@Composable
fun WithUiZoom(prefs: UiPreferences, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val zoom = (prefs.appearance.uiZoom / 100f).coerceIn(0.5f, 2.5f)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDensity provides Density(base.density * zoom, base.fontScale * zoom),
    ) {
        content()
    }
}

@Composable
private fun AppRoot(session: YapSession, orch: Orchestrator, state: OrchestratorState) {
    // Global overlay: reset/start failures must be visible in every state,
    // not just Running (MainShell is not composed elsewhere).
    Box(Modifier.fillMaxSize()) {
        when (state) {
            OrchestratorState.Created -> SplashScreen("YapYap")
            OrchestratorState.Starting -> {
                val progress by orch.bootProgress.collectAsState()
                BootSplash(progress)
            }
            OrchestratorState.SetupRequired -> SetupScreen(session)
            OrchestratorState.Running -> {
                val runtime = remember(state) { runCatching { session.orchestrator?.runtime() }.getOrNull() }
                val transportStatus by orch.transportStatus.collectAsState()
                if (runtime != null) MainShell(
                    session,
                    runtime,
                    transportStatus
                ) else SplashScreen(stringResource(Res.string.splash_loading))
            }

            is OrchestratorState.ResetRequired -> ResetScreen(session, state.reason, state.details)
            is OrchestratorState.Failed -> FailureScreen(session, state.cause)
            OrchestratorState.Stopping -> SplashScreen(stringResource(Res.string.splash_stopping))
            OrchestratorState.Stopped -> StoppedScreen(session)
        }
        val toast by session.toast.collectAsState()
        // Toasts dismiss themselves after a few seconds; clicking dismisses sooner.
        LaunchedEffect(toast?.atMillis) {
            val shown = toast ?: return@LaunchedEffect
            kotlinx.coroutines.delay(4_000)
            if (session.toast.value?.atMillis == shown.atMillis) session.clearToast()
        }
        // W4 recovery key: staged by SetupController, survives the setup → shell
        // navigation. Above content, below toasts.
        val pendingKey by session.pendingRecoveryKey.collectAsState()
        val keyCopiedMsg = stringResource(Res.string.toast_key_copied)
        pendingKey?.let { key ->
            RecoveryKeyModal(
                recoveryKey = key,
                onAcknowledge = { session.acknowledgeRecoveryKey() },
                onCopied = { session.showToast(keyCopiedMsg, ToastKind.Success) },
            )
        }
        ToastHost(
            toast,
            onDismiss = { session.clearToast() },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}

@Composable
fun SplashScreen(line: String) {
    val colors = yapColors
    Box(
        Modifier.fillMaxSize().background(colors.bgApp).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("✦", style = yapType.headingXxl.copy(color = colors.brand))
            Spacer(Modifier.height(12.dp))
            BasicText("YapYap", style = yapType.headingXxl.copy(color = colors.textStrong))
            Spacer(Modifier.height(8.dp))
            BasicText(line, style = yapType.bodySm.copy(color = colors.textMuted))
        }
    }
}

@Composable
private fun BootSplash(progress: BootProgress) {
    val colors = yapColors
    val detail = when (progress.phase) {
        BootPhase.STARTING -> stringResource(Res.string.splash_starting)
        BootPhase.TRANSPORTS -> stringResource(Res.string.boot_transports)
        BootPhase.DATABASE -> stringResource(Res.string.boot_database)
        BootPhase.ROUTER -> stringResource(Res.string.boot_router)
        BootPhase.FOLDS ->
            if (progress.roomsTotal > 0) {
                stringResource(Res.string.boot_folding, progress.roomsDone, progress.roomsTotal)
            } else {
                stringResource(Res.string.boot_folding_plain)
            }

        BootPhase.READY -> stringResource(Res.string.boot_ready)
    }
    Box(
        Modifier.fillMaxSize().background(colors.bgApp).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("✦", style = yapType.headingXxl.copy(color = colors.brand))
            Spacer(Modifier.height(12.dp))
            BasicText("YapYap", style = yapType.headingXxl.copy(color = colors.textStrong))
            Spacer(Modifier.height(8.dp))
            BasicText(stringResource(Res.string.boot_tagline), style = yapType.bodySm.copy(color = colors.textMuted))
            Spacer(Modifier.height(4.dp))
            BasicText(detail, style = yapType.bodySm.copy(color = colors.textMuted))
            if (progress.roomsTotal > 0) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.widthIn(max = 280.dp).fillMaxWidth().height(4.dp)
                        .background(colors.bgSurfaceHigh),
                ) {
                    val fraction =
                        progress.roomsDone.toFloat() / progress.roomsTotal.toFloat().coerceAtLeast(1f)
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .background(colors.brand),
                    )
                }
            }
        }
    }
}

@Composable
private fun StoppedScreen(session: YapSession) {
    val colors = yapColors
    Box(
        Modifier.fillMaxSize().background(colors.bgApp).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                stringResource(Res.string.stopped_title),
                style = yapType.headingXl.copy(color = colors.textStrong)
            )
            Spacer(Modifier.height(12.dp))
            YapButton(stringResource(Res.string.stopped_restart), onClick = { session.start() })
            Spacer(Modifier.height(8.dp))
            YapButton(
                stringResource(Res.string.reset_button),
                onClick = { session.resetAndReboot() },
                variant = YapButtonVariant.Danger,
            )
        }
    }
}
