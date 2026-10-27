package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.yapyap.gui.components.Avatar
import org.yapyap.gui.components.StatusDot
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.identity.AccountView
import org.yapyap.orchestrator.runtime.identity.AvailabilityLabel

@Composable
fun UserPanel(
    local: AccountView?,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    Row(
        modifier.background(colors.bgRaised).fillMaxWidth().height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (local != null) {
            Box {
                Avatar(local.accountId, local.displayName, size = 32.dp)
                StatusDot(
                    local.availability.label == AvailabilityLabel.ONLINE,
                    Modifier.align(Alignment.BottomEnd).size(10.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    local.displayName,
                    style = yapType.bodySmMedium.copy(color = colors.textDefault),
                    maxLines = 1,
                )
                BasicText(
                    local.availability.label.name.lowercase(),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                    maxLines = 1,
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        BasicText(
            "⚙",
            style = yapType.bodyLg.copy(color = colors.iconDefault),
            modifier = Modifier.clickable { onSettings() }.padding(8.dp),
        )
    }
}
