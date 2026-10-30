package org.yapyap.orchestrator

import org.yapyap.transport.tor.backend.TorBootstrapProblem
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState

/** Before the first start and after a wipe: nothing connected yet. */
internal val INITIAL_TRANSPORT_STATUS: LocalTransportStatus =
    LocalTransportStatus.Connecting(progress = 0, problem = null)

/**
 * Lifts transport vocabulary (Tor daemon health + onion publish state) to the
 * public [LocalTransportStatus]. Precedence: no network, then Tor's own
 * connection state, and only once Tor is ready the publish state.
 */
internal fun localTransportStatus(health: TorHealth, publish: TorPublishState): LocalTransportStatus =
    when (health) {
        TorHealth.NoNetwork -> LocalTransportStatus.NoNetwork
        TorHealth.Stopped, TorHealth.Starting -> LocalTransportStatus.Connecting(progress = 0, problem = null)
        is TorHealth.Bootstrapping -> LocalTransportStatus.Connecting(
            progress = health.progress,
            problem = health.problem?.toTransportProblem(),
        )

        TorHealth.NetworkDown -> LocalTransportStatus.Reconnecting
        TorHealth.Ready -> when (publish) {
            TorPublishState.PUBLISHING -> LocalTransportStatus.WarmingUp
            TorPublishState.PUBLISHED -> LocalTransportStatus.Online
            TorPublishState.DEGRADED -> LocalTransportStatus.Degraded
        }
    }

/** Tor's bootstrap `REASON` codes that mean "can't reach relays". */
private val RELAY_UNREACHABLE_REASONS = setOf("CONNECTREFUSED", "CONNECTRESET", "TIMEOUT", "NOROUTE", "IOERROR", "DONE")

internal fun TorBootstrapProblem.toTransportProblem(): TransportProblem = TransportProblem(
    kind = if (reason in RELAY_UNREACHABLE_REASONS) TransportProblemKind.RELAYS_UNREACHABLE else TransportProblemKind.OTHER,
    detail = buildString {
        append(reason)
        hostAddr?.let { append(" to ").append(it) }
        warning?.let { append(" (").append(it).append(')') }
        count?.takeIf { it > 1 }?.let { append(", ").append(it).append(" times") }
    },
)
