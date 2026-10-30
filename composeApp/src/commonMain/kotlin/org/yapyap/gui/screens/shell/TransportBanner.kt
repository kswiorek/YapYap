package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.LocalTransportStatus
import org.yapyap.orchestrator.TransportProblem
import org.yapyap.orchestrator.TransportProblemKind

/**
 * General "network not ready" banner: no network, Tor still connecting or
 * reconnecting, or our own onion not dialable yet. Renders nothing when
 * Online — the common steady state. Shown for every node state that reaches
 * the shell, newcomer and fresh-genesis sponsor alike.
 *
 * Tor's own complaints while connecting stay behind "Details": Tor often
 * recovers from them within seconds, so they never drive the headline.
 */
@Composable
fun TransportBanner(status: LocalTransportStatus) {
    val colors = yapColors
    val (line, hint, warn) = when (status) {
        LocalTransportStatus.Online -> return
        LocalTransportStatus.NoNetwork -> Triple(
            "No network connection.",
            "Messages will be sent when you're back online.",
            true,
        )

        is LocalTransportStatus.Connecting -> Triple(
            "Connecting to Tor… ${status.progress}%",
            "Messages will be sent once connected.",
            false,
        )

        LocalTransportStatus.Reconnecting -> Triple(
            "Reconnecting to Tor…",
            "Messages will be sent once connected.",
            false,
        )

        LocalTransportStatus.WarmingUp -> Triple(
            "Connected — your address isn't reachable yet. Invites may take a few minutes.",
            "Your address warms up after every restart or network change — this clears on its own.",
            false,
        )

        LocalTransportStatus.Degraded -> Triple(
            "Tor can't make your address reachable — check your connection.",
            "Still retrying underneath.",
            true,
        )
    }
    val problem = (status as? LocalTransportStatus.Connecting)?.problem
    var showDetails by remember(problem) { mutableStateOf(false) }
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
                hint,
                style = yapType.bodyXs.copy(color = if (warn) colors.warning else colors.textMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
            if (problem != null && showDetails) {
                BasicText(
                    problem.describe(),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (problem != null) {
            BasicText(
                if (showDetails) "Hide" else "Details",
                style = yapType.bodyXs.copy(color = colors.textSubtle),
                modifier = Modifier.clickable { showDetails = !showDetails }.padding(4.dp),
            )
        }
    }
}

private fun TransportProblem.describe(): String = when (kind) {
    TransportProblemKind.RELAYS_UNREACHABLE -> "Some Tor relays can't be reached from this network: $detail"
    TransportProblemKind.OTHER -> "Tor reported: $detail"
}
