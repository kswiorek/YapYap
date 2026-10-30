package org.yapyap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.yapyap.gui.capabilities.Capabilities
import org.yapyap.gui.capabilities.LocalCapabilities
import org.yapyap.gui.components.ToastHost
import org.yapyap.gui.components.YapButton
import org.yapyap.gui.components.YapButtonVariant
import org.yapyap.gui.screens.reset.FailureScreen
import org.yapyap.gui.screens.reset.ResetScreen
import org.yapyap.gui.screens.setup.RecoveryKeyModal
import org.yapyap.gui.screens.setup.SetupScreen
import org.yapyap.gui.screens.shell.MainShell
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.theme.YapTheme
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.BootPhase
import org.yapyap.orchestrator.BootProgress
import org.yapyap.orchestrator.Orchestrator
import org.yapyap.orchestrator.OrchestratorState

@Composable
fun App(session: YapSession?) {
    YapTheme {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalCapabilities provides Capabilities(),
        ) {
            if (session == null) {
                SplashScreen("YapYap")
            } else {
                val orchState by session.orchestratorFlow.collectAsState()
                val orch = orchState
                if (orch == null) {
                    LaunchedEffect(session) { session.start() }
                    SplashScreen("Starting…")
                } else {
                    val state by orch.state.collectAsState()
                    AppRoot(session, orch, state)
                }
            }
        }
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
                if (runtime != null) MainShell(session, runtime, transportStatus) else SplashScreen("Loading…")
            }

            is OrchestratorState.ResetRequired -> ResetScreen(session, state.reason, state.details)
            is OrchestratorState.Failed -> FailureScreen(session, state.cause)
            OrchestratorState.Stopping -> SplashScreen("Stopping…")
            OrchestratorState.Stopped -> StoppedScreen(session)
        }
        val toast by session.toast.collectAsState()
        // W4 recovery key: staged by SetupController, survives the setup → shell
        // navigation. Above content, below toasts.
        val pendingKey by session.pendingRecoveryKey.collectAsState()
        pendingKey?.let { key ->
            RecoveryKeyModal(
                recoveryKey = key,
                onAcknowledge = { session.acknowledgeRecoveryKey() },
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
        BootPhase.STARTING -> "Starting…"
        BootPhase.TRANSPORTS -> "Opening transports…"
        BootPhase.DATABASE -> "Opening database…"
        BootPhase.ROUTER -> "Starting router…"
        BootPhase.FOLDS ->
            if (progress.roomsTotal > 0) {
                "Folding rooms ${progress.roomsDone}/${progress.roomsTotal}…"
            } else {
                "Folding…"
            }

        BootPhase.READY -> "Almost there…"
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
            BasicText("Starting network…", style = yapType.bodySm.copy(color = colors.textMuted))
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
            BasicText("Stopped", style = yapType.headingXl.copy(color = colors.textStrong))
            Spacer(Modifier.height(12.dp))
            YapButton("Restart", onClick = { session.start() })
            Spacer(Modifier.height(8.dp))
            YapButton(
                "Reset app",
                onClick = { session.resetAndReboot() },
                variant = YapButtonVariant.Danger,
            )
        }
    }
}
