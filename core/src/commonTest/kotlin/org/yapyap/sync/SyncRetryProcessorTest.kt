package org.yapyap.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.sync.SyncRetryProcessor
import org.yapyap.time.FixedEpochProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class SyncRetryProcessorTest {

    private val localDevice = PeerId("retry-local-device")
    private val remoteDevice = PeerId("retry-remote-device")
    private val remoteAccount = AccountId("retry-remote-account")
    private val roomId = RoomId(Uuid.random())
    private val now = 10_000L

    private fun buildProcessor(
        stack: SyncRoutingStack,
        policy: FixedSyncPeerPolicy,
        repo: FakePendingSyncRepository,
    ): SyncRetryProcessor =
        SyncRetryProcessor(
            ctx = stack.ctx,
            pendingSyncs = repo,
            systemSender = stack.systemSender,
            peerPolicy = policy,
            peerAvailabilityRegistry = PeerAvailabilityRegistry(stack.ctx.timeProvider, MutableStateFlow(RouterConfig())),
            maxIdlePoll = MutableStateFlow(1),
        )

    @Test
    fun runIn_dueSync_sendsSyncRequestToEligibleDevice() = runBlocking {
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
            time = FixedEpochProvider(now),
        )
        val repo = FakePendingSyncRepository()
        val syncId = Uuid.random()
        repo.insertSync(
            syncId = syncId, roomId = roomId,
            anchorLamport = 0L, orphanLamport = 5L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = now,
        )
        val processor = buildProcessor(stack, FixedSyncPeerPolicy(nextDevice = remoteDevice), repo)

        val scope = CoroutineScope(SupervisorJob())
        val job = processor.runIn(scope)
        delay(500)
        job.cancel()
        scope.cancel()

        assertEquals(1, stack.tor.sends.size)
        assertEquals(1, repo.all().single().attempts)
    }

    @Test
    fun runIn_noEligibleDevice_reschedulesWithOfflineBackoff() = runBlocking {
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
            time = FixedEpochProvider(now),
        )
        val repo = FakePendingSyncRepository()
        val syncId = Uuid.random()
        repo.insertSync(
            syncId = syncId, roomId = roomId,
            anchorLamport = 0L, orphanLamport = 5L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = now,
        )
        val processor = buildProcessor(stack, FixedSyncPeerPolicy(nextDevice = null), repo)

        val scope = CoroutineScope(SupervisorJob())
        val job = processor.runIn(scope)
        delay(500)
        job.cancel()
        scope.cancel()

        assertEquals(0, stack.tor.sends.size)
        assertEquals(now + 60L, repo.nextAttemptAtOf(syncId))
    }
}
