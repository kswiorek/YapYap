package org.yapyap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.yapyap.gui.capabilities.Capabilities
import org.yapyap.gui.capabilities.LocalCapabilities
import org.yapyap.gui.components.YapButton
import org.yapyap.gui.components.YapButtonVariant
import org.yapyap.gui.screens.reset.FailureScreen
import org.yapyap.gui.screens.reset.ResetScreen
import org.yapyap.gui.screens.setup.SetupScreen
import org.yapyap.gui.screens.shell.MainShell
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.theme.YapTheme
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
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
                    AppRoot(session, state)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(session: YapSession, state: OrchestratorState) {
    when (state) {
        OrchestratorState.Created -> SplashScreen("YapYap")
        OrchestratorState.Starting -> SplashScreen("Starting network…")
        OrchestratorState.SetupRequired -> SetupScreen(session)
        OrchestratorState.Running -> {
            val runtime = remember(state) { runCatching { session.orchestrator?.runtime() }.getOrNull() }
            if (runtime != null) MainShell(session, runtime) else SplashScreen("Loading…")
        }

        is OrchestratorState.ResetRequired -> ResetScreen(session, state.reason, state.details)
        is OrchestratorState.Failed -> FailureScreen(session, state.cause)
        OrchestratorState.Stopping -> SplashScreen("Stopping…")
        OrchestratorState.Stopped -> StoppedScreen(session)
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
private fun StoppedScreen(session: YapSession) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
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
                onClick = {
                    scope.launch { runCatching { session.orchestrator?.resetApp() } }
                },
                variant = YapButtonVariant.Danger,
            )
        }
    }
}
