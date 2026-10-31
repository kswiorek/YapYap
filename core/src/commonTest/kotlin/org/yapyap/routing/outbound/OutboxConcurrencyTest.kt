package org.yapyap.routing.outbound

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.persistence.packet.OutboxEntry
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The outbox has no processing round to wait for: a slow send (an offline peer can
 * hold Tor for minutes) must never delay anything queued behind it. Found in practice:
 * a sponsor's intro sat three minutes behind a send to an offline device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxConcurrencyTest {

    private val local = PeerId("concurrencylocalaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val slowPeer = PeerId("concurrencyslowbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val fastPeer = PeerId("concurrencyfastcccccccccccccccccccccccccccccccccccccccccccccccccccc")
    private val slowTor = TorEndpoint("slow.onion", 80)
    private val fastTor = TorEndpoint("fast.onion", 80)
    private val nowSeconds = 10_000L

    private class CountingOutbox(val delegate: TrackingPacketOutbox = TrackingPacketOutbox()) :
        PacketOutbox by delegate {
        var listDueCalls = 0
        override suspend fun listDue(now: Instant): List<OutboxEntry> {
            listDueCalls++
            return delegate.listDue(now)
        }
    }

    private class Harness(
        val tor: RecordingTorTransport,
        val outbox: CountingOutbox,
        val clock: FakeClock,
        val processor: OutboxProcessor,
        val slowGate: CompletableDeferred<Unit>,
        val noLinkPeers: List<PeerId>,
    )

    private fun TestScope.harness(
        online: (PeerId) -> Boolean = { true },
        routerConfig: RouterConfig = RouterConfig(retryLoopMaxIdlePoll = 60.seconds),
        startLoop: Boolean = true,
    ): Harness {
        val tor = RecordingTorTransport()
        val slowGate = CompletableDeferred<Unit>()
        tor.beforeSend = { target, _ -> if (target == slowTor) slowGate.await() }
        val outbox = CountingOutbox()
        val clock = FakeClock(epochSeconds(nowSeconds))
        val noLinkPeers = mutableListOf<PeerId>()
        val identity = FakeIdentityResolverForRouter(
            localDevice = DeviceIdentityRecord(
                deviceId = local,
                signing = IdentityPublicKeyRecord(IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
                encryption = IdentityPublicKeyRecord(IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
            ),
            peersByAccount = mapOf(AccountId("concurrency-account") to listOf(slowPeer, fastPeer)),
            torByPeer = mutableMapOf(slowPeer to slowTor, fastPeer to fastTor),
        )
        val processor = outboxProcessorUnderTest(
            tor = tor,
            identity = identity,
            outbox = outbox,
            clock = clock,
            routerConfig = routerConfig,
            isPeerOnline = online,
            onNoLink = { noLinkPeers += it },
        )
        if (startLoop) processor.runIn(backgroundScope)
        runCurrent()
        return Harness(tor, outbox, clock, processor, slowGate, noLinkPeers)
    }

    private fun envelope(target: PeerId, type: PacketType = PacketType.MESSAGE) = BinaryEnvelope(
        packetId = Uuid.random(),
        packetType = type,
        dispositionRequested = true,
        createdAt = epochSeconds(nowSeconds),
        expiresAt = epochSeconds(nowSeconds + 3_600),
        source = local,
        target = target,
        payload = byteArrayOf(1, 2, 3),
    )

    private suspend fun Harness.enqueue(target: PeerId, targetEndpoint: TorEndpoint? = null) =
        processor.enqueueAndWake(envelope(target), clock.now(), targetEndpoint = targetEndpoint)

    private fun Harness.sendsTo(endpoint: TorEndpoint) = tor.sends.count { it.first == endpoint }

    @Test
    fun slowSend_doesNotDelayEntriesQueuedBehindIt() = runTest {
        val h = harness()
        h.enqueue(slowPeer)
        runCurrent()
        assertEquals(0, h.sendsTo(slowTor), "slow send is still in progress")

        h.enqueue(fastPeer)
        runCurrent()
        assertEquals(1, h.sendsTo(fastTor), "a send queued behind the slow one goes out at once")

        h.slowGate.complete(Unit)
        runCurrent()
        assertEquals(1, h.sendsTo(slowTor))
    }

    @Test
    fun inFlightEntry_isNeverDispatchedTwice_evenOnceDueAgain() = runTest {
        val h = harness()
        var started = 0
        h.tor.beforeSend = { target, _ ->
            if (target == slowTor) {
                started++; h.slowGate.await()
            }
        }
        h.enqueue(slowPeer)
        runCurrent()
        // Long past its retry time while the first send still hangs.
        h.clock.advanceBy(10.minutes)
        h.processor.wake()
        runCurrent()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(1, started, "no second send of the same entry while the first is in flight")
    }

    @Test
    fun perTargetCap_holdsBack_andReleasesWhenASlotFrees() = runTest {
        val h = harness(routerConfig = RouterConfig(retryLoopMaxIdlePoll = 60.seconds, outboxMaxInFlightPerTarget = 2))
        var started = 0
        val gate = CompletableDeferred<Unit>()
        h.tor.beforeSend = { target, _ ->
            if (target == slowTor) {
                started++; gate.await()
            }
        }
        repeat(3) { h.enqueue(slowPeer) }
        runCurrent()
        assertEquals(2, started, "only two sends to one peer at a time")

        gate.complete(Unit)
        runCurrent()
        assertEquals(3, started, "the held-back entry goes as soon as a slot frees")
    }

    @Test
    fun globalCap_limitsSendsAcrossPeers() = runTest {
        val h = harness(routerConfig = RouterConfig(retryLoopMaxIdlePoll = 60.seconds, outboxMaxInFlight = 1))
        h.enqueue(slowPeer)
        h.enqueue(fastPeer)
        runCurrent()
        assertEquals(0, h.sendsTo(fastTor), "the only slot is taken by the slow send")

        h.slowGate.complete(Unit)
        runCurrent()
        assertEquals(1, h.sendsTo(fastTor))
    }

    @Test
    fun idleLoop_doesNotSpin() = runTest {
        val h = harness()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertTrue(h.outbox.listDueCalls <= 2, "idle outbox polled ${h.outbox.listDueCalls} times in 10 s")
    }

    @Test
    fun peerWithoutLink_waitsForOne_withoutDialingOrCountingAnAttempt() = runTest {
        val h = harness()
        h.slowGate.complete(Unit)
        h.tor.unlinkedEndpoints += slowTor
        h.enqueue(slowPeer)
        h.enqueue(fastPeer)
        runCurrent()

        assertEquals(0, h.sendsTo(slowTor), "no link: the outbox must not dial")
        assertEquals(1, h.sendsTo(fastTor), "a linked peer is unaffected")
        assertEquals(listOf(slowPeer), h.noLinkPeers, "the link keeper hears about the unlinked peer")
        val parked = h.outbox.delegate.listAllForTarget(slowPeer).single()
        assertEquals(0L, parked.attempts, "a missing link is no attempt at this packet")
        assertEquals(h.clock.now() + RouterConfig().noLinkRecheckDelay, parked.nextRetryAt)
    }

    @Test
    fun parkedEntries_goAsSoonAsTheirLinkComesUp() = runTest {
        val h = harness()
        h.slowGate.complete(Unit)
        h.tor.unlinkedEndpoints += slowTor
        h.enqueue(slowPeer)
        runCurrent()
        assertEquals(0, h.sendsTo(slowTor))

        h.tor.unlinkedEndpoints -= slowTor
        h.processor.onPeerOnline(slowPeer)
        runCurrent()
        assertEquals(1, h.sendsTo(slowTor))
    }

    @Test
    fun bootstrapEntry_withExplicitEndpoint_stillDialsWithoutALink() = runTest {
        val h = harness()
        h.slowGate.complete(Unit)
        h.tor.unlinkedEndpoints += slowTor
        h.enqueue(slowPeer, targetEndpoint = slowTor)
        runCurrent()
        assertEquals(1, h.sendsTo(slowTor), "a newcomer has no link to wait for: one-shot dial")
        assertEquals(listOf<kotlin.time.Duration?>(null), h.tor.sendBudgets, "with the transport's full budget")
    }

    @Test
    fun notRunning_processDueWaitsForItsSends() = runTest {
        val h = harness(startLoop = false)
        h.slowGate.complete(Unit)
        h.outbox.enqueue(envelope(fastPeer), h.clock.now())
        h.processor.processDue()
        assertEquals(1, h.sendsTo(fastTor))
    }
}
