package org.yapyap.routing.outbound


import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RelayBackup
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Relays back up a direct message only once the direct path looks doubtful — no link, no ACK
 * in time, or a retry — and at most once per message. While a link delivers, they carry nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxRelayBackupTest {

    private val local = PeerId("backuplocalaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val peer = PeerId("backuppeerbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val peerTor = TorEndpoint("peer.onion", 80)
    private val relay = PeerId("backuprelaycccccccccccccccccccccccccccccccccccccccccccccccccccccc")
    private val relayTor = TorEndpoint("relay.onion", 80)
    private val nowSeconds = 10_000L
    private val config = RouterConfig(retryLoopMaxIdlePoll = 60.seconds, relayBackupAfter = 30.seconds)

    private class StaticRelaySelection(val relays: List<PeerId>) : RelaySelectionPolicy {
        override suspend fun selectRelays(targetDevice: PeerId): List<PeerId> = relays
    }

    private inner class Harness(
        val tor: RecordingTorTransport,
        val outbox: TrackingPacketOutbox,
        val processor: OutboxProcessor,
        val clock: FakeClock,
    ) {
        /** Relay copies made so far (copies target the relay; the direct targets the peer). */
        val backedUp: List<BinaryEnvelope> get() = outbox.enqueued.filter { it.target == relay }
    }

    private fun TestScope.harness(linked: Boolean = true): Harness {
        val tor = RecordingTorTransport()
        if (!linked) tor.unlinkedEndpoints += peerTor
        val outbox = TrackingPacketOutbox()
        val clock = FakeClock(epochSeconds(nowSeconds))
        val identity = FakeIdentityResolverForRouter(
            localDevice = DeviceIdentity(
                deviceId = local,
                signingKey = SigningPublicKey(byteArrayOf(1)),
                encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
            ),
            peersByAccount = mapOf(AccountId("backup-account") to listOf(peer)),
            torByPeer = mutableMapOf(peer to peerTor, relay to relayTor),
        )
        val processor = outboxProcessorUnderTest(
            tor = tor,
            identity = identity,
            outbox = outbox,
            clock = clock,
            routerConfig = config,
            relaySelection = StaticRelaySelection(listOf(relay)),
        )
        processor.runIn(backgroundScope)
        runCurrent()
        return Harness(tor, outbox, processor, clock)
    }

    private fun message(type: PacketType = PacketType.MESSAGE) = BinaryEnvelope(
        packetId = Uuid.random(),
        packetType = type,
        dispositionRequested = true,
        createdAt = epochSeconds(nowSeconds),
        expiresAt = epochSeconds(nowSeconds + 3_600),
        source = local,
        target = peer,
        routingHeader = byteArrayOf(1, 2, 3),
        content = ByteArray(0),
        signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
    )

    private suspend fun Harness.enqueue(envelope: BinaryEnvelope, backup: RelayBackup = RelayBackup.PENDING) =
        processor.enqueueAndWake(
            envelope,
            clock.now(),
            relayBackup = backup,
            // Relay copies are keyed by the row's tag kind: an untagged row names nobody
            // waiting on its outcome, so it never owes a backup.
            tag = OutboxTag(OutboxTag.Kind.MESSAGE, Uuid.random()),
        )

    @Test
    fun acknowledgedInTime_relaysCarryNothing() = runTest {
        val h = harness()
        val msg = message()
        h.enqueue(msg)
        runCurrent()
        assertEquals(1, h.tor.sends.size, "went straight over the link")

        h.processor.onDisposition(msg.packetId)
        advanceTimeBy(31.seconds)
        runCurrent()
        assertTrue(h.backedUp.isEmpty())
    }

    @Test
    fun notAcknowledgedInTime_isBackedUpOnce() = runTest {
        val h = harness()
        val msg = message()
        h.enqueue(msg)
        runCurrent()

        advanceTimeBy(29.seconds)
        runCurrent()
        assertTrue(h.backedUp.isEmpty(), "still within the ACK window")

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, h.backedUp.size)
        assertContentEquals(msg.routingHeader, h.backedUp.single().routingHeader)
        assertContentEquals(msg.content, h.backedUp.single().content)
        assertEquals(RelayBackup.DEPOSITED, h.outbox.relayBackupOf(msg.packetId))

        // The retry (the attempt went unacknowledged) finds the backup already made.
        h.clock.advanceBy(config.torRetryDelay)
        h.processor.wake()
        advanceTimeBy(config.torRetryDelay)
        runCurrent()
        assertEquals(1, h.backedUp.size, "never twice")
    }

    @Test
    fun noLink_isBackedUpWhenItWaits() = runTest {
        val h = harness(linked = false)
        val msg = message()
        h.enqueue(msg)
        runCurrent()

        // No link to the peer: the direct copy waits, the relay copy goes (relay is linked).
        assertEquals(1, h.backedUp.size)
        assertEquals(1, h.tor.sends.size, "the relay copy goes while the direct waits")
        assertEquals(relay, h.tor.sends.single().second.target)
    }

    @Test
    fun retryAfterRestart_backsUpWithoutTheAckTimer() = runTest {
        // The in-memory ACK timer died with the process; the stored attempt count tells.
        val h = harness()
        val msg = message()
        h.outbox.enqueue(
            msg,
            h.clock.now(),
            relayBackup = RelayBackup.PENDING,
            tag = OutboxTag(OutboxTag.Kind.MESSAGE, Uuid.random()),
        )
        h.outbox.recordAttempt(msg.packetId, h.clock.now(), h.clock.now())
        h.processor.wake()
        runCurrent()

        assertEquals(1, h.backedUp.size)
    }

    @Test
    fun packetsWithoutABackup_neverGetOne() = runTest {
        val h = harness(linked = false)
        h.enqueue(message(), backup = RelayBackup.NONE)
        h.enqueue(message(PacketType.SYSTEM), backup = RelayBackup.NONE)
        runCurrent()
        advanceTimeBy(31.seconds)
        runCurrent()

        assertTrue(h.backedUp.isEmpty())
    }
}
