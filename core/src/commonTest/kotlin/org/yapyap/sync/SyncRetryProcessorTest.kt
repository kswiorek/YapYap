package org.yapyap.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.yapyap.persistence.packet.OutboxTag
import org.yapyap.protocol.*
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.InMemorySyncAttemptStore
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.sync.SyncRetryProcessor
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.session.SessionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class SyncRetryProcessorTest {

    private val localDevice = PeerId("retry-local-device")
    private val remoteDevice = PeerId("retry-remote-device")
    private val otherDevice = PeerId("retry-other-device")
    private val remoteAccount = AccountId("retry-remote-account")
    private val roomId = RoomId(Uuid.random())
    private val now = epochSeconds(10_000L)

    private class Harness(
        val stack: SyncRoutingStack,
        val pending: FakePendingSyncRepository,
        val attempts: InMemorySyncAttemptStore,
        val processor: SyncRetryProcessor,
        val nudged: List<PeerId>,
    ) {
        suspend fun runFor(millis: Long = 500) {
            val scope = CoroutineScope(SupervisorJob())
            val job = processor.runIn(scope)
            delay(millis)
            job.cancel()
            scope.cancel()
        }
    }

    /** [policy] null: the real one, with every device in [devices] Tor-linked unless in [unlinked]. */
    private suspend fun harness(
        policy: SyncPeerPolicy? = null,
        devices: List<PeerId> = listOf(remoteDevice),
        unlinked: Set<PeerId> = emptySet(),
    ): Harness {
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(remoteAccount to devices),
            torByPeer = devices.associateWith { TorEndpoint("${it.id}.onion", 80) }.toMutableMap(),
            clock = FakeClock(now),
        )
        devices.forEach { device ->
            val endpoint = TorEndpoint("${device.id}.onion", 80)
            stack.ctx.peerLinks.bindTorEndpoint(device, endpoint)
            if (device in unlinked) stack.tor.unlinkedEndpoints += endpoint
        }
        val pending = FakePendingSyncRepository()
        val attempts = InMemorySyncAttemptStore()
        val nudged = mutableListOf<PeerId>()
        val processor = SyncRetryProcessor(
            ctx = stack.ctx,
            attempts = attempts,
            syncs = RecordingSyncPayloadProvider(pending = pending),
            systemSender = stack.systemSender,
            outboxProcessor = stack.outboxProcessor,
            peerPolicy = policy ?: DefaultSyncPeerPolicy(stack.ctx),
            maxIdlePoll = MutableStateFlow(1.seconds),
            onNoLink = { nudged += it },
        )
        return Harness(stack, pending, attempts, processor, nudged)
    }

    private suspend fun Harness.pendingSync(): Uuid {
        val syncId = Uuid.random()
        pending.insertSync(syncId, roomId, targetMessageId = Uuid.random(), candidateAccounts = listOf(remoteAccount))
        processor.start(syncId, firstAttemptAt = now)
        return syncId
    }

    @Test
    fun dueSync_queuesOneTaggedRequestInTheOutbox() = runBlocking {
        val h = harness(FixedSyncPeerPolicy(nextDevice = remoteDevice))
        val syncId = h.pendingSync()

        h.runFor()

        val queued = h.stack.outbox.enqueued.single()
        assertEquals(remoteDevice, queued.target)
        assertEquals(PacketType.SYSTEM, queued.packetType)
        assertTrue(queued.dispositionRequested, "the answer (ACK or NACK) is what moves the sync on")
        assertEquals(OutboxTag(OutboxTag.Kind.SYNC, syncId), h.stack.outbox.tagOf(queued.packetId))
        assertEquals(1, h.attempts.attemptsOf(syncId))
    }

    @Test
    fun attemptStillInOutbox_isNotAskedAgain() = runBlocking {
        val h = harness(FixedSyncPeerPolicy(nextDevice = remoteDevice))
        val syncId = h.pendingSync()
        h.runFor()
        h.attempts.advanceToNow(listOf(syncId), now)

        h.runFor()

        assertEquals(1, h.stack.outbox.enqueued.size)
        assertEquals(1, h.attempts.attemptsOf(syncId), "waiting on an attempt doesn't count as one")
        assertEquals(now + 60.seconds, h.attempts.nextAttemptAt(syncId))
    }

    @Test
    fun satisfiedSync_isDroppedWithoutAsking() = runBlocking {
        val h = harness(FixedSyncPeerPolicy(nextDevice = remoteDevice))
        val syncId = h.pendingSync()
        h.pending.deleteSync(syncId)

        h.runFor()

        assertTrue(h.stack.outbox.enqueued.isEmpty())
        assertFalse(h.attempts.contains(syncId))
    }

    @Test
    fun nack_declinesThatDevice_andAsksTheNextAtOnce() = runBlocking {
        val h = harness(devices = listOf(remoteDevice, otherDevice))
        val syncId = h.pendingSync()
        h.runFor()
        val first = h.stack.outbox.enqueued.single()
        h.stack.outboxProcessor.onDisposition(first.packetId, PacketNackReason.DECLINED)
        h.processor.onAttemptOutcome(
            OutboxOutcome.Nacked(OutboxTag(OutboxTag.Kind.SYNC, syncId), remoteDevice, PacketNackReason.DECLINED),
        )

        h.runFor()

        assertEquals(setOf(remoteDevice), h.attempts.declinedDevices(syncId))
        assertEquals(listOf(remoteDevice, otherDevice), h.stack.outbox.enqueued.map { it.target })
    }

    @Test
    fun reopen_onlyWhenEveryCandidateDeclined() = runBlocking {
        val h = harness(devices = listOf(remoteDevice, otherDevice))
        val syncId = h.pendingSync()
        h.attempts.addDeclined(syncId, remoteDevice)

        h.processor.reopen(syncId, remoteAccount)
        assertEquals(setOf(remoteDevice), h.attempts.declinedDevices(syncId), "mid-round: another device is untried")

        h.attempts.addDeclined(syncId, otherDevice)
        h.processor.reopen(syncId, remoteAccount)
        assertEquals(emptySet(), h.attempts.declinedDevices(syncId))
    }

    @Test
    fun attempt_expiresAfterTheSyncAttemptTimeout() = runBlocking {
        val h = harness()
        h.pendingSync()

        h.runFor()

        val queued = h.stack.outbox.enqueued.single()
        assertEquals(now + RouterConfig().syncAttemptTimeout, queued.expiresAt)
    }

    @Test
    fun unansweredAttempt_movesToTheNextDevice_withoutDecliningIt() = runBlocking {
        val h = harness(devices = listOf(remoteDevice, otherDevice))
        val syncId = h.pendingSync()
        h.runFor()
        val tag = OutboxTag(OutboxTag.Kind.SYNC, syncId)
        h.stack.outboxProcessor.onDisposition(h.stack.outbox.enqueued.single().packetId)  // gone, like an expiry
        h.processor.onAttemptOutcome(OutboxOutcome.Expired(tag, remoteDevice))

        h.runFor()

        assertEquals(listOf(remoteDevice, otherDevice), h.stack.outbox.enqueued.map { it.target })
        assertEquals(emptySet(), h.attempts.declinedDevices(syncId), "a silent device isn't refusing")
    }

    @Test
    fun nonDeclineNack_isTreatedLikeExpiry() = runBlocking {
        val h = harness(devices = listOf(remoteDevice, otherDevice))
        val syncId = h.pendingSync()
        val tag = OutboxTag(OutboxTag.Kind.SYNC, syncId)

        h.processor.onAttemptOutcome(OutboxOutcome.Nacked(tag, remoteDevice, PacketNackReason.EXPIRED))
        h.processor.onAttemptOutcome(
            OutboxOutcome.Nacked(
                tag,
                otherDevice,
                PacketNackReason.PERMANENT_PROTECTION_FAILED
            )
        )

        assertEquals(emptySet(), h.attempts.declinedDevices(syncId))
    }

    @Test
    fun lastSilentDevice_waitsForTheBackoff() = runBlocking {
        val h = harness()
        val syncId = h.pendingSync()
        h.runFor()
        val backoff = h.attempts.nextAttemptAt(syncId)
        h.stack.outboxProcessor.onDisposition(h.stack.outbox.enqueued.single().packetId)

        h.processor.onAttemptOutcome(OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.SYNC, syncId), remoteDevice))

        assertEquals(backoff, h.attempts.nextAttemptAt(syncId), "nobody else to ask: not every timeout")
    }

    @Test
    fun noLinkedCandidate_nudgesTheirLinks_andAsksWhenOneComesUp() = runBlocking {
        val h = harness(unlinked = setOf(remoteDevice))
        val syncId = h.pendingSync()
        val scope = CoroutineScope(SupervisorJob())
        h.processor.runIn(scope)
        delay(500)
        assertTrue(h.stack.outbox.enqueued.isEmpty(), "nobody is asked without a link")
        assertEquals(listOf(remoteDevice), h.nudged.distinct())
        assertEquals(now + 30.seconds, h.attempts.nextAttemptAt(syncId))

        h.stack.tor.setSessionState(TorEndpoint("${remoteDevice.id}.onion", 80), SessionPhase.CONNECTED)
        delay(500)
        scope.cancel()

        assertEquals(listOf(remoteDevice), h.stack.outbox.enqueued.map { it.target }, "asked as soon as linked")
    }

    @Test
    fun noEligibleDevice_backsOffExponentially() = runBlocking {
        val h = harness(FixedSyncPeerPolicy(nextDevice = null))
        val syncId = h.pendingSync()

        h.runFor()

        // No sendable candidate: the sync is inert, not dead — it backs off
        // exponentially (30s at attempts=0) instead of dying by age.
        assertTrue(h.stack.outbox.enqueued.isEmpty())
        assertEquals(now + 30L.seconds, h.attempts.nextAttemptAt(syncId))
        assertEquals(1, h.attempts.attemptsOf(syncId))
    }

    @Test
    fun noEligibleDevice_backoffGrowsWithAttempts() = runBlocking {
        val h = harness(FixedSyncPeerPolicy(nextDevice = null))
        val syncId = h.pendingSync()
        h.attempts.recordAttempt(syncId, now)

        h.runFor()

        // attempts=1 → 30 * 2^1 = 60s; the inert sync gets cheaper per round,
        // capped at an hour by computeBackoff.
        assertTrue(h.stack.outbox.enqueued.isEmpty())
        assertEquals(now + 60L.seconds, h.attempts.nextAttemptAt(syncId))
        assertEquals(2, h.attempts.attemptsOf(syncId))
    }
}
