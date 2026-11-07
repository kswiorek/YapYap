package org.yapyap.orchestrator

import org.yapyap.routing.router.LinkProblem
import org.yapyap.routing.router.LinkProblemKind
import org.yapyap.routing.router.LinkRecoveryStep
import org.yapyap.routing.router.LocalLinkStatus

/** Before the first start and after a wipe: nothing connected yet. */
internal val INITIAL_TRANSPORT_STATUS: LocalTransportStatus =
    LocalTransportStatus.Connecting(progress = 0, problem = null)

/** Lifts the router's [LocalLinkStatus] to the public [LocalTransportStatus], state for state. */
internal fun LocalLinkStatus.toTransportStatus(): LocalTransportStatus = when (this) {
    LocalLinkStatus.NoNetwork -> LocalTransportStatus.NoNetwork
    is LocalLinkStatus.Connecting -> LocalTransportStatus.Connecting(progress, problem?.toTransportProblem())
    LocalLinkStatus.Reconnecting -> LocalTransportStatus.Reconnecting
    is LocalLinkStatus.Recovering -> LocalTransportStatus.Recovering(
        action = step.toRecoveryAction(),
        progress = progress,
        problem = problem?.toTransportProblem(),
    )

    LocalLinkStatus.WarmingUp -> LocalTransportStatus.WarmingUp
    LocalLinkStatus.Online -> LocalTransportStatus.Online
    LocalLinkStatus.Degraded -> LocalTransportStatus.Degraded
}

private fun LinkRecoveryStep.toRecoveryAction(): RecoveryAction = when (this) {
    LinkRecoveryStep.RECONNECTING -> RecoveryAction.RECONNECTING
    LinkRecoveryStep.RESTARTING -> RecoveryAction.RESTARTING
    LinkRecoveryStep.REFRESHING_DIRECTORY -> RecoveryAction.REFRESHING_DIRECTORY
    LinkRecoveryStep.RESETTING_ENTRY_RELAYS -> RecoveryAction.RESETTING_ENTRY_RELAYS
}

private fun LinkProblem.toTransportProblem(): TransportProblem = TransportProblem(
    kind = when (kind) {
        LinkProblemKind.RELAYS_UNREACHABLE -> TransportProblemKind.RELAYS_UNREACHABLE
        LinkProblemKind.OTHER -> TransportProblemKind.OTHER
    },
    detail = detail,
)

internal fun LocalTransportStatus.problemOrNull(): TransportProblem? = when (this) {
    is LocalTransportStatus.Connecting -> problem
    is LocalTransportStatus.Recovering -> problem
    else -> null
}
