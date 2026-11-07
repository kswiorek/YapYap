package org.yapyap.routing.router

import org.yapyap.transport.tor.backend.TorBootstrapProblem
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import org.yapyap.transport.tor.backend.TorRecoveryStep

/**
 * This device's own connectivity, in router vocabulary: the transports' health folded into one
 * state. Device-local (no peer involved); the orchestrator lifts it to its public status.
 */
internal sealed interface LocalLinkStatus {
    /** The device has no usable network connection. */
    data object NoNetwork : LocalLinkStatus

    /** Connecting to the network ([progress] 0–99 %); [problem] is detail, not a verdict. */
    data class Connecting(val progress: Int, val problem: LinkProblem?) : LocalLinkStatus

    /** Was connected but lost the network; reconnects on its own. */
    data object Reconnecting : LocalLinkStatus

    /**
     * Stuck while the network is fine and being fixed automatically ([step] is the current one).
     * [progress] is the bootstrap percentage while reconnecting, null once only our address is
     * being re-proven.
     */
    data class Recovering(val step: LinkRecoveryStep, val progress: Int?, val problem: LinkProblem?) : LocalLinkStatus

    /** Connected, but our address isn't proven dialable yet. */
    data object WarmingUp : LocalLinkStatus

    /** Connected and our address is dialable. */
    data object Online : LocalLinkStatus

    /** Connected, but our address could not be proven dialable within the budget; still retrying. */
    data object Degraded : LocalLinkStatus
}

/** Why connecting is in trouble. [detail] is diagnostic text (reason, relay address), not user copy. */
internal data class LinkProblem(val kind: LinkProblemKind, val detail: String)

internal enum class LinkProblemKind {
    /** The relays tried can't be reached (refused, no route, timeouts): a blocked or broken network. */
    RELAYS_UNREACHABLE,

    /** Anything else. */
    OTHER,
}

/** What automatic recovery is doing, cheapest first. None of it changes our address. */
internal enum class LinkRecoveryStep {
    RECONNECTING,
    RESTARTING,
    REFRESHING_DIRECTORY,
    RESETTING_ENTRY_RELAYS,
}

/**
 * Folds Tor daemon health, onion publish state and the current recovery step into one
 * [LocalLinkStatus]. Precedence: no network, then an active recovery (until Tor is up and
 * published again), then Tor's own connection state, and only once Tor is ready the publish state.
 */
internal fun localLinkStatus(
    health: TorHealth,
    publish: TorPublishState,
    recovery: TorRecoveryStep? = null,
): LocalLinkStatus {
    if (recovery != null && health != TorHealth.NoNetwork &&
        !(health == TorHealth.Ready && publish == TorPublishState.PUBLISHED)
    ) {
        val bootstrapping = health as? TorHealth.Bootstrapping
        return LocalLinkStatus.Recovering(
            step = recovery.toLinkRecoveryStep(),
            progress = when (health) {
                TorHealth.Ready -> null
                is TorHealth.Bootstrapping -> health.progress
                else -> 0
            },
            problem = bootstrapping?.problem?.toLinkProblem(),
        )
    }
    return when (health) {
        TorHealth.NoNetwork -> LocalLinkStatus.NoNetwork
        TorHealth.Stopped, TorHealth.Starting -> LocalLinkStatus.Connecting(progress = 0, problem = null)
        is TorHealth.Bootstrapping -> LocalLinkStatus.Connecting(
            progress = health.progress,
            problem = health.problem?.toLinkProblem(),
        )

        TorHealth.NetworkDown -> LocalLinkStatus.Reconnecting
        TorHealth.Ready -> when (publish) {
            TorPublishState.PUBLISHING -> LocalLinkStatus.WarmingUp
            TorPublishState.PUBLISHED -> LocalLinkStatus.Online
            TorPublishState.DEGRADED -> LocalLinkStatus.Degraded
        }
    }
}

private fun TorRecoveryStep.toLinkRecoveryStep(): LinkRecoveryStep = when (this) {
    TorRecoveryStep.CYCLE_NETWORK -> LinkRecoveryStep.RECONNECTING
    TorRecoveryStep.RESTART_DAEMON -> LinkRecoveryStep.RESTARTING
    TorRecoveryStep.PURGE_DIRECTORY_CACHE -> LinkRecoveryStep.REFRESHING_DIRECTORY
    TorRecoveryStep.RESET_GUARD_STATE -> LinkRecoveryStep.RESETTING_ENTRY_RELAYS
}

/** Tor's bootstrap `REASON` codes that mean "can't reach relays". */
private val RELAY_UNREACHABLE_REASONS = setOf("CONNECTREFUSED", "CONNECTRESET", "TIMEOUT", "NOROUTE", "IOERROR", "DONE")

internal fun TorBootstrapProblem.toLinkProblem(): LinkProblem = LinkProblem(
    kind = if (reason in RELAY_UNREACHABLE_REASONS) LinkProblemKind.RELAYS_UNREACHABLE else LinkProblemKind.OTHER,
    detail = buildString {
        append(reason)
        hostAddr?.let { append(" to ").append(it) }
        warning?.let { append(" (").append(it).append(')') }
        count?.takeIf { it > 1 }?.let { append(", ").append(it).append(" times") }
    },
)
