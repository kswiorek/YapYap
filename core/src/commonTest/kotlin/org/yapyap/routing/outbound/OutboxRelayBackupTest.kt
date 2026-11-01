package org.yapyap.routing.outbound

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.persistence.packet.RelayBackup
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
    private val nowSeconds = 10_000L
    private val config = RouterConfig(retryLoopMaxIdlePoll = 60.seconds, relayBackupAfter = 30.seconds)

    private class Harness(
        val tor: RecordingTorTransport,
        val outbox: TrackingPacketOutbox,
        val processor: OutboxProcessor,
        val clock: FakeClock,
        val backedUp: MutableList<Uuid>,
    )

    private fun TestScope.harness(linked: Boolean = true): Harness {
        val tor = RecordingTorTransport()
        if (!linked) tor.unlinkedEndpoints += peerTor
        val outbox = TrackingPacketOutbox()
        val clock = FakeClock(epochSeconds(nowSeconds))
        val backedUp = mutableListOf<Uuid>()
        val identity = FakeIdentityResolverForRouter(
            localDevice = DeviceIdentityRecord(
                deviceId = local,
                signing = IdentityPublicKeyRecord(IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
                encryption = IdentityPublicKeyRecord(IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
            ),
            peersByAccount = mapOf(AccountId("backup-account") to listOf(peer)),
            torByPeer = mutableMapOf(peer to peerTor),
        )
        val processor = outboxProcessorUnderTest(
            tor = tor,
            identity = identity,
            outbox = outbox,
            clock = clock,
            routerConfig = config,
            relayCopies = { envelope -> backedUp += envelope.packetId; 2 },
        )
        processor.runIn(backgroundScope)
        runCurrent()
        return Harness(tor, outbox, processor, clock, backedUp)
    }

    private fun message(type: PacketType = PacketType.MESSAGE) = BinaryEnvelope(
        packetId = Uuid.random(),
        packetType = type,
        dispositionRequested = true,
        createdAt = epochSeconds(nowSeconds),
        expiresAt = epochSeconds(nowSeconds + 3_600),
        source = local,
        target = peer,
        payload = byteArrayOf(1, 2, 3),
    )

    private suspend fun Harness.enqueue(envelope: BinaryEnvelope, backup: RelayBackup = RelayBackup.PENDING) =
        processor.enqueueAndWake(envelope, clock.now(), relayBackup = backup)

    @Test
    fun acknowledgedInTime_relaysCarryNothing() = runTest {
        val h = harness()
        val msg = message()
        h.enqueue(msg)
        runCurrent()
        assertEquals(1, h.tor.sends.size, "went straight over the link")

        h.processor.onOutboundPacketDelivered(msg.packetId)
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(emptyList(), h.backedUp)
    }

    @Test
    fun notAcknowledgedInTime_isBackedUpOnce() = runTest {
        val h = harness()
        val msg = message()
        h.enqueue(msg)
        runCurrent()

        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals(emptyList(), h.backedUp, "still within the ACK window")

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf(msg.packetId), h.backedUp)
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

        assertEquals(0, h.tor.sends.size)
        assertEquals(listOf(msg.packetId), h.backedUp)
    }

    @Test
    fun retryAfterRestart_backsUpWithoutTheAckTimer() = runTest {
        // The in-memory ACK timer died with the process; the stored attempt count tells.
        val h = harness()
        val msg = message()
        h.outbox.enqueue(msg, h.clock.now(), relayBackup = RelayBackup.PENDING)
        h.outbox.recordAttempt(msg.packetId, h.clock.now(), h.clock.now())
        h.processor.wake()
        runCurrent()

        assertEquals(listOf(msg.packetId), h.backedUp)
    }

    @Test
    fun packetsWithoutABackup_neverGetOne() = runTest {
        val h = harness(linked = false)
        h.enqueue(message(), backup = RelayBackup.NONE)
        h.enqueue(message(PacketType.SYSTEM), backup = RelayBackup.NONE)
        runCurrent()
        advanceTimeBy(31.seconds)
        runCurrent()

        assertEquals(emptyList(), h.backedUp)
    }
}
