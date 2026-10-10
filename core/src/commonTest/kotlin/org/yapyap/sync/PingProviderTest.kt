package org.yapyap.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.ping.FrontierSnapshotProvider
import org.yapyap.routing.ping.PingProvider
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.PingFrontiers
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.sync.RemovalRePusher
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Ping threading (docs/room events.md §6): the sender account is resolved in
 * the routing layer and the flow stays account-level; the one device-granular
 * op — re-opening the pinger on the pinged rooms' inert rows — runs here
 * against the repository directly, so no device id ever leaves the router.
 */
class PingProviderTest {

    private val localDevice = PeerId("ping-local-device")
    private val remoteDevice = PeerId("ping-remote-device")
    private val otherDevice = PeerId("ping-other-device")
    private val remoteAccount = AccountId("ping-remote-account")
    private val otherAccount = AccountId("ping-other-account")
    private val roomId = RoomId(Uuid.random())
    private val otherRoom = RoomId(Uuid.random())
    private val now = epochSeconds(10_000L)

    private fun buildProvider(
        stack: SyncRoutingStack,
        pendingRepo: FakePendingSyncRepository,
        pingFlow: MutableSharedFlow<PingFrontiers>,
    ): PingProvider = PingProvider(
        ctx = stack.ctx,
        config = MutableStateFlow(RouterConfig()),
        pingPayloadFlow = pingFlow,
        frontierSnapshotProvider = object : FrontierSnapshotProvider {
            override suspend fun latestRoomFrontiers(peerId: PeerId) = emptyList<Pair<RoomId, List<Uuid>>>()
        },
        systemSender = stack.systemSender,
        peerAvailabilityRegistry = PeerAvailabilityRegistry(
            stack.ctx.clock,
            MutableStateFlow(RouterConfig()),
            FakePeerAvailabilityStore(),
        ),
        pendingSyncs = pendingRepo,
        // Null removal node: the re-push is a no-op in these tests
        // (covered separately in the test pass).
        removalRePusher = RemovalRePusher(
            syncPayloadProvider = RecordingSyncPayloadProvider(),
            outboundMessenger = stack.outboundMessenger,
            routerConfig = MutableStateFlow(RouterConfig()),
        ),
    )

    private fun replyPing(vararg rooms: Pair<RoomId, List<Uuid>>): SystemPayload.Ping =
        SystemPayload.Ping(
            pingId = Uuid.random(),
            isReply = true,
            selfReportedAvailability = 0.5,
            roomFrontiers = rooms.toList(),
        )

    private fun stackWith(
        pendingRepo: FakePendingSyncRepository =
            FakePendingSyncRepository(devicesByAccount = mapOf(remoteAccount to listOf(remoteDevice))),
    ): Pair<SyncRoutingStack, FakePendingSyncRepository> {
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
            clock = FakeClock(now),
        )
        return stack to pendingRepo
    }

    private suspend fun insertInertRow(
        repo: FakePendingSyncRepository,
        room: RoomId,
        target: Uuid = Uuid.random(),
        candidates: List<AccountId> = listOf(remoteAccount),
        attempted: Set<PeerId> = setOf(remoteDevice),
    ): Uuid {
        val syncId = Uuid.random()
        repo.insertSync(
            syncId = syncId, roomId = room,
            targetMessageId = target,
            candidateAccounts = candidates, nextAttemptAt = now,
        )
        attempted.forEach { repo.addAttemptedPeer(syncId, it) }
        return syncId
    }

    @Test
    fun handlePing_reply_emitsResolvedSenderAccount() = runTest {
        val (stack, pendingRepo) = stackWith()
        val pingFlow = MutableSharedFlow<PingFrontiers>(replay = 1, extraBufferCapacity = 64)
        val provider = buildProvider(stack, pendingRepo, pingFlow)
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
        val (stack, pendingRepo) = stackWith()
        val pingFlow = MutableSharedFlow<PingFrontiers>(replay = 1, extraBufferCapacity = 64)
        val provider = buildProvider(stack, pendingRepo, pingFlow)
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
    fun handlePing_reopensAttemptedDeviceOnInertRow() = runTest {
        val (stack, pendingRepo) = stackWith()
        val provider = buildProvider(stack, pendingRepo, MutableSharedFlow(extraBufferCapacity = 64))
        val syncId = insertInertRow(pendingRepo, roomId)

        // The ping is the device's assertion that its projection has us in
        // the room — contradicting its own gate-NACK, so the NACK is stale.
        provider.handlePing(remoteDevice, replyPing(roomId to listOf(Uuid.random())))

        assertTrue(pendingRepo.getAttemptedDevices(syncId).isEmpty())
    }

    @Test
    fun handlePing_doesNotReopenMidRound() = runTest {
        // Inertness gate (mirrors DefaultSyncPeerPolicy's eligibility half):
        // while another candidate device is still un-attempted, the ping must
        // not clear anything — otherwise routine pings would collapse rotation
        // and the tier-preferred device would hog every request.
        val repo = FakePendingSyncRepository(
            devicesByAccount = mapOf(
                remoteAccount to listOf(remoteDevice),
                otherAccount to listOf(otherDevice),
            ),
        )
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(
                remoteAccount to listOf(remoteDevice),
                otherAccount to listOf(otherDevice),
            ),
            clock = FakeClock(now),
        )
        val provider = buildProvider(stack, repo, MutableSharedFlow(extraBufferCapacity = 64))
        val syncId = insertInertRow(
            repo, roomId,
            candidates = listOf(remoteAccount, otherAccount),
            attempted = setOf(remoteDevice),
        )

        provider.handlePing(remoteDevice, replyPing(roomId to listOf(Uuid.random())))

        assertEquals(setOf(remoteDevice), repo.getAttemptedDevices(syncId))
    }

    @Test
    fun handlePing_reopenIsRoomScoped() = runTest {
        val (stack, pendingRepo) = stackWith()
        val provider = buildProvider(stack, pendingRepo, MutableSharedFlow(extraBufferCapacity = 64))
        val syncA = insertInertRow(pendingRepo, roomId)
        val syncB = insertInertRow(pendingRepo, otherRoom)

        // Ping advertises only roomId: otherRoom's rows keep their attempted set.
        provider.handlePing(remoteDevice, replyPing(roomId to listOf(Uuid.random())))

        assertTrue(pendingRepo.getAttemptedDevices(syncA).isEmpty())
        assertEquals(setOf(remoteDevice), pendingRepo.getAttemptedDevices(syncB))
    }
}
