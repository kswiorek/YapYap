package org.yapyap.orchestrator

import org.yapyap.routing.router.LinkProblem
import org.yapyap.routing.router.LinkProblemKind
import org.yapyap.routing.router.LinkRecoveryStep
import org.yapyap.routing.router.LocalLinkStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class TransportStatusMappingTest {

    @Test
    fun plainStates_mapOneToOne() {
        assertEquals(LocalTransportStatus.NoNetwork, LocalLinkStatus.NoNetwork.toTransportStatus())
        assertEquals(LocalTransportStatus.Reconnecting, LocalLinkStatus.Reconnecting.toTransportStatus())
        assertEquals(LocalTransportStatus.WarmingUp, LocalLinkStatus.WarmingUp.toTransportStatus())
        assertEquals(LocalTransportStatus.Online, LocalLinkStatus.Online.toTransportStatus())
        assertEquals(LocalTransportStatus.Degraded, LocalLinkStatus.Degraded.toTransportStatus())
    }

    @Test
    fun connecting_keepsProgressAndProblem() {
        assertEquals(
            LocalTransportStatus.Connecting(
                progress = 5,
                problem = TransportProblem(TransportProblemKind.RELAYS_UNREACHABLE, "NOROUTE"),
            ),
            LocalLinkStatus.Connecting(5, LinkProblem(LinkProblemKind.RELAYS_UNREACHABLE, "NOROUTE"))
                .toTransportStatus(),
        )
    }

    @Test
    fun recovering_mapsEveryStep() {
        val expected = mapOf(
            LinkRecoveryStep.RECONNECTING to RecoveryAction.RECONNECTING,
            LinkRecoveryStep.RESTARTING to RecoveryAction.RESTARTING,
            LinkRecoveryStep.REFRESHING_DIRECTORY to RecoveryAction.REFRESHING_DIRECTORY,
            LinkRecoveryStep.RESETTING_ENTRY_RELAYS to RecoveryAction.RESETTING_ENTRY_RELAYS,
        )
        LinkRecoveryStep.entries.forEach { step ->
            assertEquals(
                LocalTransportStatus.Recovering(
                    action = expected.getValue(step),
                    progress = null,
                    problem = TransportProblem(TransportProblemKind.OTHER, "IDENTITY"),
                ),
                LocalLinkStatus.Recovering(step, null, LinkProblem(LinkProblemKind.OTHER, "IDENTITY"))
                    .toTransportStatus(),
            )
        }
    }
}
