package org.yapyap.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.ping.FrontierSnapshotProvider
import org.yapyap.routing.ping.PingProvider
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.PingFrontiers
import org.yapyap.routing.router.RouterConfig
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Ping threading (docs/room events.md §6): the sender account is resolved in
 * the routing layer and the flow stays account-level, both ways — frontiers
 * are asked for per account and received ones go up per account. What they
 * mean (sync, reopening declined syncs, removal re-push) is the orchestrator's
 * (DefaultSyncCoordinatorTest).
 */
class PingProviderTest {

    private val localDevice = PeerId("ping-local-device")
    private val remoteDevice = PeerId("ping-remote-device")
    private val remoteAccount = AccountId("ping-remote-account")
    private val roomId = RoomId(Uuid.random())
    private val now = epochSeconds(10_000L)

    private fun buildProvider(
        stack: SyncRoutingStack,
        pingFlow: MutableSharedFlow<PingFrontiers>,
        frontiersAskedFor: MutableList<AccountId> = mutableListOf(),
    ): PingProvider = PingProvider(
        ctx = stack.ctx,
        config = MutableStateFlow(RouterConfig()),
        pingPayloadFlow = pingFlow,
        frontierSnapshotProvider = object : FrontierSnapshotProvider {
            override suspend fun latestRoomFrontiers(account: AccountId): List<Pair<RoomId, List<Uuid>>> {
                frontiersAskedFor += account
                return emptyList()
            }
        },
        systemSender = stack.systemSender,
        peerAvailabilityRegistry = PeerAvailabilityRegistry(
            stack.ctx.clock,
            MutableStateFlow(RouterConfig()),
            FakePeerAvailabilityStore(),
        ),
    )

    private fun replyPing(vararg rooms: Pair<RoomId, List<Uuid>>): SystemPayload.Ping =
        SystemPayload.Ping(
            pingId = Uuid.random(),
            isReply = true,
            selfReportedAvailability = 0.5,
            roomFrontiers = rooms.toList(),
        )

    private fun stack(): SyncRoutingStack =
        buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
            torByPeer = mutableMapOf(remoteDevice to TorEndpoint("ping-remote.onion", 80)),
            clock = FakeClock(now),
        )

    @Test
    fun handlePing_reply_emitsResolvedSenderAccount() = runTest {
        val stack = stack()
        val pingFlow = MutableSharedFlow<PingFrontiers>(replay = 1, extraBufferCapacity = 64)
        val provider = buildProvider(stack, pingFlow)
        val received = mutableListOf<PingFrontiers>()
        val collectJob = launch { pingFlow.collect { received.add(it) } }
        val tip = Uuid.random()

        provider.handlePing(remoteDevice, replyPing(roomId to listOf(tip)))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(PingFrontiers(remoteAccount, listOf(roomId to listOf(tip)))), received)
        collectJob.cancel()
    }

    @Test
    fun handlePing_unknownDevice_emitsNullSenderButKeepsFrontiers() = runTest {
        // Known-room frontier sync doesn't need the sender — only the
        // unknown-room candidate path does (§6.2), so the ping is forwarded
        // with a null sender instead of being dropped.
        val stack = stack()
        val pingFlow = MutableSharedFlow<PingFrontiers>(replay = 1, extraBufferCapacity = 64)
        val provider = buildProvider(stack, pingFlow)
        val received = mutableListOf<PingFrontiers>()
        val collectJob = launch { pingFlow.collect { received.add(it) } }
        val unknownDevice = PeerId("ping-unknown-device")
        val tip = Uuid.random()

        provider.handlePing(unknownDevice, replyPing(roomId to listOf(tip)))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(PingFrontiers(null, listOf(roomId to listOf(tip)))), received)
        collectJob.cancel()
    }

    @Test
    fun probe_isAnsweredWithFrontiersForTheSendersAccount() = runTest {
        val stack = stack()
        val askedFor = mutableListOf<AccountId>()
        val provider = buildProvider(stack, MutableSharedFlow(extraBufferCapacity = 64), askedFor)

        provider.handlePing(remoteDevice, replyPing().copy(isReply = false))

        assertEquals(listOf(remoteAccount), askedFor)
        assertEquals(1, stack.tor.sends.size, "the probe is answered")
    }
}
