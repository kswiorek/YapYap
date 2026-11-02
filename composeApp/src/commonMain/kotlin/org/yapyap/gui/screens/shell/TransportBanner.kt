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
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.LocalTransportStatus
import org.yapyap.orchestrator.RecoveryAction
import org.yapyap.orchestrator.TransportProblem
import org.yapyap.orchestrator.TransportProblemKind
import yapyap.composeapp.generated.resources.* // Res + generated string accessors

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
    val pendingHint = stringResource(Res.string.transport_pending_hint)
    val autoHint = stringResource(Res.string.transport_auto_hint)
    val (line, hint, warn) = when (status) {
        LocalTransportStatus.Online -> return
        LocalTransportStatus.NoNetwork -> Triple(
            stringResource(Res.string.transport_no_network),
            stringResource(Res.string.transport_no_network_hint),
            true,
        )

        is LocalTransportStatus.Connecting -> Triple(
            stringResource(Res.string.transport_connecting, status.progress),
            pendingHint,
            false,
        )

        LocalTransportStatus.Reconnecting -> Triple(
            stringResource(Res.string.transport_reconnecting),
            pendingHint,
            false,
        )

        is LocalTransportStatus.Recovering -> {
            val action = status.action.describeText()
            val progress = status.progress
            Triple(
                if (progress != null) stringResource(Res.string.transport_recovering_progress, action, progress)
                else stringResource(Res.string.transport_recovering, action),
                autoHint,
                false,
            )
        }

        LocalTransportStatus.WarmingUp -> Triple(
            stringResource(Res.string.transport_warming),
            stringResource(Res.string.transport_warming_hint),
            false,
        )

        LocalTransportStatus.Degraded -> Triple(
            stringResource(Res.string.transport_unreachable),
            stringResource(Res.string.transport_unreachable_hint),
            true,
        )
    }
    val problem = when (status) {
        is LocalTransportStatus.Connecting -> status.problem
        is LocalTransportStatus.Recovering -> status.problem
        else -> null
    }
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
                    problem.describeText(),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (problem != null) {
            BasicText(
                if (showDetails) stringResource(Res.string.transport_hide) else stringResource(Res.string.transport_details),
                style = yapType.bodyXs.copy(color = colors.textSubtle),
                modifier = Modifier.clickable { showDetails = !showDetails }.padding(4.dp),
            )
        }
    }
}

@Composable
private fun RecoveryAction.describeText(): String = when (this) {
    RecoveryAction.RECONNECTING -> stringResource(Res.string.transport_action_reconnecting)
    RecoveryAction.RESTARTING -> stringResource(Res.string.transport_action_restarting)
    RecoveryAction.REFRESHING_DIRECTORY -> stringResource(Res.string.transport_action_refreshing)
    RecoveryAction.RESETTING_ENTRY_RELAYS -> stringResource(Res.string.transport_action_resetting)
}

@Composable
private fun TransportProblem.describeText(): String = when (kind) {
    TransportProblemKind.RELAYS_UNREACHABLE -> stringResource(Res.string.transport_problem_relays, detail)
    TransportProblemKind.OTHER -> stringResource(Res.string.transport_problem_other, detail)
}
