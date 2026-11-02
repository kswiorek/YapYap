package org.yapyap.gui.screens.reset

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.*
import org.yapyap.gui.copy.ResetReasonText
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.boot.ResetReason
import yapyap.composeapp.generated.resources.*

@Composable
fun ResetScreen(session: YapSession, reason: ResetReason, details: String) {
    val colors = yapColors
    var typed by remember { mutableStateOf("") }
    val canReset = typed == "reset"
    CenterColumn {
        androidx.compose.foundation.layout.Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth()
                .onEnterConfirm(enabled = canReset) { session.resetAndReboot() },
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            BasicText("⚠", style = yapType.headingXxl.copy(color = colors.warning))
            Spacer(Modifier.height(8.dp))
            BasicText(
                ResetReasonText.title(reason),
                style = yapType.headingXl.copy(color = colors.textStrong),
            )
            BasicText(
                ResetReasonText.body(reason),
                style = yapType.bodySm.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 8.dp),
            )
            if (details.isNotBlank()) {
                BasicText(
                    stringResource(Res.string.reset_details, details),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            BasicText(
                ResetReasonText.nextStep(reason),
                style = yapType.bodySm.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            YapTextField(typed, { typed = it }, placeholder = stringResource(Res.string.reset_type_to_confirm))
            Spacer(Modifier.height(12.dp))
            YapButton(
                stringResource(Res.string.reset_button),
                onClick = {
                    // Stops first (reset refuses Running), wipes, then boots
                    // back into SetupRequired. Failures surface as toasts.
                    session.resetAndReboot()
                },
                variant = YapButtonVariant.Danger,
                modifier = Modifier.fillMaxWidth(),
                enabled = canReset,
            )
        }
    }
}

@Composable
fun FailureScreen(session: YapSession, cause: Throwable) {
    val colors = yapColors
    var showDetails by remember { mutableStateOf(false) }
    CenterColumn {
        androidx.compose.foundation.layout.Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            BasicText("✕", style = yapType.headingXxl.copy(color = colors.danger))
            Spacer(Modifier.height(8.dp))
            BasicText(
                stringResource(Res.string.failure_title),
                style = yapType.headingXl.copy(color = colors.textStrong),
            )
            BasicText(
                cause.message ?: cause.toString(),
                style = yapType.bodySm.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            YapButton(
                if (showDetails) stringResource(Res.string.failure_toggle_hide) else stringResource(Res.string.failure_toggle_show),
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
                stringResource(Res.string.failure_retry),
                onClick = { session.start() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            YapButton(
                stringResource(Res.string.failure_reset),
                onClick = {
                    // Stops first (reset refuses Running), wipes, then boots
                    // back into SetupRequired. Failures surface as toasts.
                    session.resetAndReboot()
                },
                variant = YapButtonVariant.Danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
