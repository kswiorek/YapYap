package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.LocalTransportState

/**
 * General "network not ready" banner: our own onion isn't dialable yet (fresh
 * boot, descriptor still publishing) or publishing gave up. Null when
 * PUBLISHED — the common steady state renders nothing. Shown for every node
 * state that reaches the shell, newcomer and fresh-genesis sponsor alike.
 */
@Composable
fun TransportBanner(state: LocalTransportState) {
    val colors = yapColors
    val line = when (state) {
        LocalTransportState.WARMING_UP ->
            "Connecting to Tor… your address isn't reachable yet — invites may take a few minutes."

        LocalTransportState.DEGRADED ->
            "Tor can't publish your address — check connectivity. Still retrying underneath."

        LocalTransportState.PUBLISHED -> return
    }
    val accent = when (state) {
        LocalTransportState.WARMING_UP -> colors.textMuted
        LocalTransportState.DEGRADED -> colors.warning
        LocalTransportState.PUBLISHED -> return
    }
    Row(
        Modifier.fillMaxWidth().background(colors.bgSurfaceHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                line,
                style = yapType.bodySm.copy(color = colors.textDefault),
            )
            BasicText(
                "Your address warms up after every restart — this clears on its own.",
                style = yapType.bodyXs.copy(color = accent),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
