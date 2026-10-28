package org.yapyap.gui.screens.reset

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.yapyap.gui.components.*
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.boot.ResetReason

@Composable
fun ResetScreen(session: YapSession, reason: ResetReason, details: String) {
    val colors = yapColors
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    CenterColumn {
        androidx.compose.foundation.layout.Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            BasicText("⚠", style = yapType.headingXxl.copy(color = colors.warning))
            Spacer(Modifier.height(8.dp))
            BasicText(
                "This device has been removed from the network",
                style = yapType.headingXl.copy(color = colors.textStrong),
            )
            BasicText(
                "Reason: $reason. $details Resetting wipes local data and returns to setup.",
                style = yapType.bodySm.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            YapTextField(typed, { typed = it }, placeholder = "Type \"reset\" to confirm")
            Spacer(Modifier.height(12.dp))
            YapButton(
                "Reset app",
                onClick = {
                    // Process lifetime: reset navigates away from this screen.
                    session.scope.launch {
                        runCatching { session.orchestrator?.resetApp() }
                            .onFailure { error = it.message }
                    }
                },
                variant = YapButtonVariant.Danger,
                modifier = Modifier.fillMaxWidth(),
                enabled = typed == "reset",
            )
            error?.let { InlineError(it) }
        }
    }
}

@Composable
fun FailureScreen(session: YapSession, cause: Throwable) {
    val colors = yapColors
    var showDetails by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    CenterColumn {
        androidx.compose.foundation.layout.Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            BasicText("✕", style = yapType.headingXxl.copy(color = colors.danger))
            Spacer(Modifier.height(8.dp))
            BasicText(
                "Something went wrong",
                style = yapType.headingXl.copy(color = colors.textStrong),
            )
            BasicText(
                cause.message ?: cause.toString(),
                style = yapType.bodySm.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            YapButton(
                if (showDetails) "Hide details" else "Technical details",
                onClick = { showDetails = !showDetails },
                variant = YapButtonVariant.Secondary,
            )
            if (showDetails) {
                SelectionContainer(Modifier.padding(top = 8.dp)) {
                    BasicText(
                        cause.stackTraceToString().take(4000),
                        style = yapType.bodyXs.copy(color = colors.textMuted),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            YapButton(
                "Retry",
                onClick = { session.start() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            YapButton(
                "Reset",
                onClick = {
                    // Process lifetime: reset navigates away from this screen.
                    session.scope.launch {
                        runCatching { session.orchestrator?.resetApp() }
                            .onFailure { error = it.message }
                    }
                },
                variant = YapButtonVariant.Danger,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { InlineError(it) }
        }
    }
}
