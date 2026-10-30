package org.yapyap.orchestrator

import org.yapyap.transport.tor.backend.TorBootstrapProblem
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import kotlin.test.Test
import kotlin.test.assertEquals

class TransportStatusMappingTest {

    private val noRoute = TorBootstrapProblem(
        reason = "NOROUTE",
        warning = "No route to host",
        count = 3,
        hostAddr = "95.216.23.120:9001",
    )

    @Test
    fun noNetwork_winsOverEverything() {
        TorPublishState.entries.forEach { publish ->
            assertEquals(LocalTransportStatus.NoNetwork, localTransportStatus(TorHealth.NoNetwork, publish))
        }
    }

    @Test
    fun beforeReady_publishStateIsIgnored() {
        assertEquals(
            LocalTransportStatus.Connecting(0, null),
            localTransportStatus(TorHealth.Starting, TorPublishState.PUBLISHED),
        )
        assertEquals(
            LocalTransportStatus.Connecting(45, null),
            localTransportStatus(TorHealth.Bootstrapping(45, null), TorPublishState.PUBLISHED),
        )
        assertEquals(
            LocalTransportStatus.Reconnecting,
            localTransportStatus(TorHealth.NetworkDown, TorPublishState.PUBLISHED),
        )
    }

    @Test
    fun ready_followsPublishState() {
        assertEquals(LocalTransportStatus.WarmingUp, localTransportStatus(TorHealth.Ready, TorPublishState.PUBLISHING))
        assertEquals(LocalTransportStatus.Online, localTransportStatus(TorHealth.Ready, TorPublishState.PUBLISHED))
        assertEquals(LocalTransportStatus.Degraded, localTransportStatus(TorHealth.Ready, TorPublishState.DEGRADED))
    }

    @Test
    fun bootstrapProblem_becomesTransportProblem() {
        assertEquals(
            LocalTransportStatus.Connecting(
                progress = 5,
                problem = TransportProblem(
                    kind = TransportProblemKind.RELAYS_UNREACHABLE,
                    detail = "NOROUTE to 95.216.23.120:9001 (No route to host), 3 times",
                ),
            ),
            localTransportStatus(TorHealth.Bootstrapping(5, noRoute), TorPublishState.PUBLISHING),
        )
    }

    @Test
    fun unknownReason_isOther_andSparseFieldsAreOmitted() {
        val identity = TorBootstrapProblem(reason = "IDENTITY", warning = null, count = 1, hostAddr = null)
        assertEquals(
            TransportProblem(TransportProblemKind.OTHER, "IDENTITY"),
            identity.toTransportProblem(),
        )
    }
}
