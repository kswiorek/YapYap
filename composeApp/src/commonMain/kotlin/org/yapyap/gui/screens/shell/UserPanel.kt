package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Cog
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.Avatar
import org.yapyap.gui.components.StatusDot
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.identity.AccountView
import org.yapyap.orchestrator.runtime.identity.AvailabilityLabel
import yapyap.composeapp.generated.resources.Res
import yapyap.composeapp.generated.resources.desc_settings

@Composable
fun UserPanel(
    local: AccountView?,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    // Fixed 56 dp footer row: avatar fixed, names flex (weight 1f + ellipsis),
    // one settings gear (the only one — the rail no longer has its own).
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
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                BasicText(
                    local.availability.label.name.lowercase(),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Box(
            Modifier.clickable { onSettings() }.padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FontAwesomeIcons.Solid.Cog,
                stringResource(Res.string.desc_settings),
                Modifier.size(20.dp),
                colors.iconDefault
            )
        }
    }
}
