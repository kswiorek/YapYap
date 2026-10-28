package org.yapyap.gui.screens.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.yapyap.gui.components.ScrimOverlay
import org.yapyap.gui.components.YapButton
import org.yapyap.gui.components.YapButtonVariant
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType

/**
 * W4 — recovery key modal. Shown once after account-founding setup (genesis,
 * new account); explicit ack, no dismiss: the key is unrecoverable otherwise.
 * Rendered at App root so it survives the SetupScreen → MainShell navigation
 * (the orchestrator is already Running when the setup result arrives).
 */
@Composable
fun RecoveryKeyModal(
    recoveryKey: String,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    var ack by remember(recoveryKey) { mutableStateOf(false) }
    ScrimOverlay(onDismiss = null, modifier = modifier, maxContentWidth = 602.dp) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgSurface)
                .padding(20.dp),
        ) {
            BasicText(
                "Save your recovery key",
                style = yapType.headingXl.copy(color = colors.textStrong),
            )
            BasicText(
                "Shown once — losing it locks you out of this account on every device.",
                style = yapType.bodySm.copy(color = colors.warning),
                modifier = Modifier.padding(top = 8.dp),
            )
            SelectionContainer(Modifier.padding(top = 12.dp)) {
                BasicText(
                    recoveryKey,
                    style = yapType.bodySm.copy(color = colors.textDefault),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bgApp, RoundedCornerShape(YapRadius.sm))
                        .padding(12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YapButton(
                    if (ack) "✓ Stored" else "I stored it",
                    onClick = { ack = !ack },
                    variant = YapButtonVariant.Secondary,
                )
                Spacer(Modifier.width(8.dp))
                YapButton(
                    "Copy key",
                    onClick = { copyToClipboard(recoveryKey) },
                    variant = YapButtonVariant.Secondary,
                )
                Spacer(Modifier.weight(1f))
                YapButton(
                    "Continue",
                    onClick = onAcknowledge,
                    enabled = ack,
                )
            }
        }
    }
}
