package org.yapyap.routing.router

import org.yapyap.transport.tor.backend.TorBootstrapProblem
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import org.yapyap.transport.tor.backend.TorRecoveryStep
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalLinkStatusTest {

    private val noRoute = TorBootstrapProblem(
        reason = "NOROUTE",
        warning = "No route to host",
        count = 3,
        hostAddr = "95.216.23.120:9001",
    )

    @Test
    fun noNetwork_winsOverEverything() {
        TorPublishState.entries.forEach { publish ->
            assertEquals(LocalLinkStatus.NoNetwork, localLinkStatus(TorHealth.NoNetwork, publish))
        }
    }

    @Test
    fun beforeReady_publishStateIsIgnored() {
        assertEquals(
            LocalLinkStatus.Connecting(0, null),
            localLinkStatus(TorHealth.Starting, TorPublishState.PUBLISHED),
        )
        assertEquals(
            LocalLinkStatus.Connecting(45, null),
            localLinkStatus(TorHealth.Bootstrapping(45, null), TorPublishState.PUBLISHED),
        )
        assertEquals(
            LocalLinkStatus.Reconnecting,
            localLinkStatus(TorHealth.NetworkDown, TorPublishState.PUBLISHED),
        )
    }

    @Test
    fun ready_followsPublishState() {
        assertEquals(LocalLinkStatus.WarmingUp, localLinkStatus(TorHealth.Ready, TorPublishState.PUBLISHING))
        assertEquals(LocalLinkStatus.Online, localLinkStatus(TorHealth.Ready, TorPublishState.PUBLISHED))
        assertEquals(LocalLinkStatus.Degraded, localLinkStatus(TorHealth.Ready, TorPublishState.DEGRADED))
    }

    @Test
    fun bootstrapProblem_becomesLinkProblem() {
        assertEquals(
            LocalLinkStatus.Connecting(
                progress = 5,
                problem = LinkProblem(
                    kind = LinkProblemKind.RELAYS_UNREACHABLE,
                    detail = "NOROUTE to 95.216.23.120:9001 (No route to host), 3 times",
                ),
            ),
            localLinkStatus(TorHealth.Bootstrapping(5, noRoute), TorPublishState.PUBLISHING),
        )
    }

    @Test
    fun unknownReason_isOther_andSparseFieldsAreOmitted() {
        val identity = TorBootstrapProblem(reason = "IDENTITY", warning = null, count = 1, hostAddr = null)
        assertEquals(
            LinkProblem(LinkProblemKind.OTHER, "IDENTITY"),
            identity.toLinkProblem(),
        )
    }

    @Test
    fun recovery_showsWhileStuck_withProgressAndProblem() {
        assertEquals(
            LocalLinkStatus.Recovering(
                step = LinkRecoveryStep.RESTARTING,
                progress = 5,
                problem = LinkProblem(
                    kind = LinkProblemKind.RELAYS_UNREACHABLE,
                    detail = "NOROUTE to 95.216.23.120:9001 (No route to host), 3 times",
                ),
            ),
            localLinkStatus(
                TorHealth.Bootstrapping(5, noRoute),
                TorPublishState.PUBLISHING,
                TorRecoveryStep.RESTART_DAEMON,
            ),
        )
        assertEquals(
            LocalLinkStatus.Recovering(LinkRecoveryStep.RECONNECTING, progress = null, problem = null),
            localLinkStatus(TorHealth.Ready, TorPublishState.DEGRADED, TorRecoveryStep.CYCLE_NETWORK),
        )
    }

    @Test
    fun recovery_neverHidesNoNetwork_orAFinishedRecovery() {
        assertEquals(
            LocalLinkStatus.NoNetwork,
            localLinkStatus(TorHealth.NoNetwork, TorPublishState.PUBLISHING, TorRecoveryStep.RESTART_DAEMON),
        )
        assertEquals(
            LocalLinkStatus.Online,
            localLinkStatus(TorHealth.Ready, TorPublishState.PUBLISHED, TorRecoveryStep.RESTART_DAEMON),
        )
    }
}
