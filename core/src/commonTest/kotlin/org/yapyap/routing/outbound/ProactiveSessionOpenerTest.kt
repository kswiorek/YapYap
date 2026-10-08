package org.yapyap.routing.outbound

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.*
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSessionPhase
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * `awaitSession` is event-driven: the session-state stream wakes the wait, a slow
 * re-open cadence covers silently lost signaling, and a stored CONNECTED is only
 * accepted while the channel is actually usable — so a stale entry delays the loop
 * by at most one interval instead of producing a false Connected.
 */
class ProactiveSessionOpenerTest {

    private val peer = PeerId("opener-peer-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

    private class Stack(
        val clock: FakeClock,
        val transport: WebRtcTransport,
        val fake: RecordingWebRtcTransport,
        val opener: ProactiveSessionOpener,
    )

    private fun stack(
        reopenDelay: Duration = 100.milliseconds,
        fake: RecordingWebRtcTransport = RecordingWebRtcTransport(),
        transport: WebRtcTransport = fake,
    ): Stack {
        val clock = FakeClock(epochSeconds(10_000L))
        val config = RouterConfig(sessionAwaitReopenDelay = reopenDelay)
        val configFlow = MutableStateFlow(config)
        val localDevice = DeviceIdentityRecord(
            deviceId = PeerId("opener-local-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            signing = IdentityPublicKeyRecord("ls", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("le", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )
        val ctx = RoutingContext(
            identityResolver = FakeIdentityResolverForRouter(localDevice = localDevice),
            packetDeduplicator = InMemoryPacketDeduplicator(),
            envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
            torTransport = RecordingTorTransport(),
            webRtcTransport = transport,
            clock = clock,
            routerConfig = configFlow,
            transportLimits = MutableStateFlow(testTransportLimits()),
        )
        ctx.localDeviceIdentity = localDevice
        val registry = PeerAvailabilityRegistry(clock, configFlow, FakePeerAvailabilityStore())
        return Stack(clock, transport, fake, ProactiveSessionOpener(ctx, registry))
    }

    /** openSession that always fails, for the failure-tolerance path. */
    private class FailingOpenTransport(delegate: WebRtcTransport) : WebRtcTransport by delegate {
        override suspend fun openSession(target: PeerId) {
            throw RuntimeException("signaling down")
        }
    }

    @Test
    fun awaitSession_alreadyUsable_returnsConnectedWithoutOpening() = runBlocking {
        val s = stack()
        s.fake.simulateEnvelopeChannelOpen(peer)

        assertEquals(SessionOutcome.Connected, s.opener.awaitSession(peer, 5.seconds))
        assertTrue(s.fake.openSessionCalls.isEmpty())
    }

    @Test
    fun awaitSession_connectedStateEvent_returnsConnectedPromptly() = runBlocking {
        val s = stack(reopenDelay = 5.seconds)
        val emitter = launch {
            delay(50.milliseconds)
            // Channel first, state second — the production order (channel OPEN emits Connected).
            s.fake.simulateEnvelopeChannelOpen(peer)
            s.fake.setSessionState(WebRtcSessionState(peer, WebRtcSessionPhase.CONNECTED))
        }
        try {
            val elapsed = measureTime {
                assertEquals(SessionOutcome.Connected, s.opener.awaitSession(peer, 10.seconds))
            }
            // Event-driven: returns on the emission (~50ms), not on the 5s reopen tick
            // (and not on the old 1s poll quantum either).
            assertTrue(elapsed < 800.milliseconds, "took $elapsed; expected prompt return on the CONNECTED event")
            assertEquals(1, s.fake.openSessionCalls.size)
        } finally {
            emitter.cancel()
        }
    }

    @Test
    fun awaitSession_staleConnectedState_neverProducesFalseConnected() = runBlocking {
        val s = stack(reopenDelay = 100.milliseconds)
        // Impostor: stored CONNECTED while the channel is still closed.
        s.fake.setSessionState(WebRtcSessionState(peer, WebRtcSessionPhase.CONNECTED))

        val waiter = async { s.opener.awaitSession(peer, 10.seconds) }
        try {
            delay(300.milliseconds)
            assertTrue(!waiter.isCompleted, "stale CONNECTED must not short-circuit the wait")
            assertTrue(s.fake.openSessionCalls.size >= 2, "keeps re-issuing the idempotent open")

            // The channel really opens; no new state emission is needed — the replayed
            // CONNECTED becomes truthful again and the wait completes on the next pass.
            s.fake.simulateEnvelopeChannelOpen(peer)
            assertEquals(SessionOutcome.Connected, withTimeout(5.seconds) { waiter.await() })
        } finally {
            waiter.cancel()
        }
    }

    @Test
    fun awaitSession_silentLoss_reopensAndTimesOut() = runBlocking {
        val s = stack(reopenDelay = 50.milliseconds)
        // The fake clock is frozen unless advanced; the ticker below drives the deadline.
        val ticker = launch {
            while (true) {
                delay(10.milliseconds)
                s.clock.advanceBy(25.milliseconds)
            }
        }
        try {
            val result = withTimeout(10.seconds) {
                s.opener.awaitSession(peer, 400.milliseconds)
            }
            assertEquals(SessionOutcome.Timeout, result)
            assertTrue(s.fake.openSessionCalls.size >= 2, "re-opens while nothing happens")
        } finally {
            ticker.cancel()
        }
    }

    @Test
    fun awaitSession_openFailure_stillTimesOut() = runBlocking {
        val fake = RecordingWebRtcTransport()
        val s = stack(reopenDelay = 50.milliseconds, fake = fake, transport = FailingOpenTransport(fake))
        val ticker = launch {
            while (true) {
                delay(10.milliseconds)
                s.clock.advanceBy(25.milliseconds)
            }
        }
        try {
            val result = withTimeout(10.seconds) {
                s.opener.awaitSession(peer, 400.milliseconds)
            }
            assertEquals(SessionOutcome.Timeout, result)
        } finally {
            ticker.cancel()
        }
    }

    @Test
    fun awaitSession_cancellationPropagates() = runBlocking {
        val s = stack(reopenDelay = 100.milliseconds)
        val waiter = async { s.opener.awaitSession(peer, 10.seconds) }
        delay(200.milliseconds)
        waiter.cancel()
        assertFailsWith<CancellationException> { waiter.await() }
        assertTrue(waiter.isCancelled)
    }
}
